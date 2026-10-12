// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::BTreeMap;

use crate::assemble::{assemble, encode};
use crate::edit::{EditedFunction, Editor, FunctionBody};
use crate::error::{HermesError, Result, invalid};
use crate::model::{
    Field, FunctionFlags, FunctionHeader, FunctionKind, Prohibit, STATIC_BUILTINS, Section,
    StringId, StringKind,
};
use crate::opcode::{IdKind, Instruction, Op};
use crate::parse::{read_u32, slice};
use crate::{FunctionId, HermesFile};

/// Identity of a module linked into this editor. Its exports remain in a
/// private environment and are initialized before the app's global code.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ModuleId(pub(crate) u32);

/// Where a linked module's IDs start in the merged file, by kind.
struct Bases {
    function: u32,
    bigint: u32,
    regexp: u32,
    shape: u32,
    switch: u32,
}

impl Editor<'_> {
    /// Links a compiled v98 JavaScript module and returns its stable identity.
    /// The module must evaluate to an exports object and keep declarations in
    /// its own lexical scope (normally an IIFE). CJS segments, global variable
    /// declarations and incompatible static-builtin assumptions are refused.
    /// Every function, string, bigint, regexp, literal and shape reference is
    /// remapped. Module debug information is dropped; exceptions are retained.
    /// The shared module initializer is assembled once when the file is written.
    pub fn link(&mut self, module: &HermesFile<'_>) -> Result<ModuleId> {
        self.transaction(|editor| editor.link_module(module))
    }

    #[expect(
        clippy::too_many_lines,
        reason = "linking visits each versioned file segment in order"
    )]
    fn link_module(&mut self, module: &HermesFile<'_>) -> Result<ModuleId> {
        if module.field(Field::CjsModuleCount) != 0 || module.field(Field::SegmentId) != 0 {
            return Err(HermesError::Unsupported(
                "extensions must be standalone execution bytecode; CommonJS segments are unsupported".into(),
            ));
        }
        if module.options() & STATIC_BUILTINS != 0 && self.file.options() & STATIC_BUILTINS == 0 {
            return Err(HermesError::Unsupported(
                "extension assumes static builtins but app does not".into(),
            ));
        }
        if module
            .function(module.global_function())?
            .instructions()?
            .iter()
            .any(|i| i.op == Op::DeclareGlobalVar)
        {
            return Err(HermesError::Unsupported(
                "extension declares globals; compile an IIFE returning exports".into(),
            ));
        }
        let mut strings = Vec::with_capacity(module.string_count() as usize);
        let mut id = 0;
        for run in module.kind_runs() {
            for _ in 0..run.count {
                let value = module.string(StringId(id))?;
                let length = value.bytes.len() / value.encoding.unit_size();
                strings.push(
                    self.append_string(
                        value.bytes.to_vec(),
                        value.encoding,
                        length as u32,
                        run.kind,
                    )?
                    .0,
                );
                id += 1;
            }
        }
        let bases = Bases {
            function: self.next_function().0,
            bigint: self.section_count(Section::BigInts),
            regexp: self.section_count(Section::Regexps),
            shape: self.section_count(Section::Shapes),
            switch: self.edits.string_switches,
        };
        let shapes = module.section(Section::Shapes).as_chunks::<8>().0;
        let mut values = Literals::new(
            module.section(Section::Values),
            &strings,
            self.section_size(Section::Values) as u32,
        );
        let mut keys = Literals::new(
            module.section(Section::Keys),
            &strings,
            self.section_size(Section::Keys) as u32,
        );
        for entry in shapes {
            let offset = keys.relocate(read_u32(entry, 0)?, read_u32(entry, 4)?)?;
            self.edits.additions[Section::Shapes as usize].extend_from_slice(&offset.to_le_bytes());
            self.edits.additions[Section::Shapes as usize].extend_from_slice(&entry[4..]);
        }
        for (table, storage) in [
            (Section::BigInts, Section::BigIntStorage),
            (Section::Regexps, Section::RegexpStorage),
        ] {
            let base = self.section_size(storage) as u32;
            for entry in module.section(table).as_chunks::<8>().0 {
                let offset = read_u32(entry, 0)?
                    .checked_add(base)
                    .ok_or_else(|| invalid(0, "literal storage offset overflow"))?;
                self.edits.additions[table as usize].extend_from_slice(&offset.to_le_bytes());
                self.edits.additions[table as usize].extend_from_slice(&entry[4..]);
            }
            self.edits.additions[storage as usize].extend_from_slice(module.section(storage));
        }
        for id in 0..module.function_count() {
            let function = module.function(FunctionId(id))?;
            let mut instructions = function.instructions()?;
            for inst in &mut instructions {
                let at = at(inst);
                let read_values = literal_count(inst, shapes)?;
                for (operand, value) in inst.definition().operands.iter().zip(&mut inst.values) {
                    *value = u64::from(match operand.id {
                        IdKind::None => continue,
                        IdKind::String => *strings
                            .get(*value as usize)
                            .ok_or_else(|| invalid(at, "string ID out of range"))?,
                        IdKind::Function => {
                            checked_id(*value, module.function_count(), bases.function)?
                        }
                        IdKind::BigInt => {
                            checked_id(*value, module.field(Field::BigIntCount), bases.bigint)?
                        }
                        IdKind::RegExp => {
                            checked_id(*value, module.field(Field::RegExpCount), bases.regexp)?
                        }
                        IdKind::Shape => {
                            checked_id(*value, module.field(Field::ObjectShapeCount), bases.shape)?
                        }
                        IdKind::Switch => checked_id(
                            *value,
                            module.field(Field::StringSwitchCount),
                            bases.switch,
                        )?,
                        IdKind::ValueBuffer => values.relocate(
                            *value as u32,
                            read_values
                                .ok_or_else(|| invalid(at, "value buffer without a length"))?,
                        )?,
                    });
                }
            }
            let mut edited = assemble(&function, instructions, Some(&strings))?;
            edited.header.name = strings[function.header.name as usize];
            self.append_function(edited);
        }
        self.edits.additions[Section::Values as usize].extend(values.output);
        self.edits.additions[Section::Keys as usize].extend(keys.output);
        self.edits.string_switches = self
            .edits
            .string_switches
            .checked_add(module.field(Field::StringSwitchCount))
            .ok_or_else(|| invalid(0, "too many string switches"))?;
        let module_id = ModuleId(self.edits.modules.len() as u32);
        self.edits
            .modules
            .push(FunctionId(bases.function + module.global_function().0));
        let exports = crate::exports::exports(module)?;
        self.edits.module_exports.push(
            exports
                .into_iter()
                .map(|id| StringId(strings[id.0 as usize]))
                .collect(),
        );
        self.refresh_bootstrap()?;
        Ok(module_id)
    }

    pub(crate) fn refresh_bootstrap(&mut self) -> Result<()> {
        if self.edits.modules.len() > u16::MAX as usize {
            return Err(invalid(0, "too many extension modules"));
        }
        if self.edits.global == self.file.global_function() {
            let name = self.intern("reseamBootstrap", StringKind::String)?;
            self.edits.global = self.append_function(generated_function(name, 1, 16, Vec::new()));
        }
        Ok(())
    }

    pub(crate) fn bootstrap(&self) -> Option<EditedFunction> {
        if self.edits.modules.is_empty() {
            return None;
        }
        let mut code = vec![
            Instruction::new(
                Op::CreateTopLevelEnvironment,
                &[0, self.edits.modules.len() as u64],
            ),
            Instruction::new(Op::LoadConstUndefined, &[1]),
            Instruction::new(Op::LoadParam, &[2, 0]),
        ];
        for (slot, function) in self.edits.modules.iter().enumerate() {
            code.extend([
                Instruction::new(Op::CreateClosureLongIndex, &[3, 1, u64::from(function.0)]),
                Instruction::new(Op::Call1, &[3, 3, 1]),
                Instruction::new(Op::StoreToEnvironmentL, &[0, slot as u64, 3]),
            ]);
        }
        code.extend([
            Instruction::new(
                Op::CreateClosureLongIndex,
                &[3, 0, u64::from(self.file.global_function().0)],
            ),
            Instruction::new(Op::Call1, &[3, 3, 2]),
            Instruction::new(Op::Ret, &[3]),
        ]);
        let name = StringId(
            self.edits.appended[(self.edits.global.0 - self.file.function_count()) as usize]
                .header
                .name,
        );
        Some(generated_function(name, 1, 16, encode(&code)))
    }
}

/// A strict function that refuses construction, as compiled for ordinary strict-mode code.
pub(crate) fn generated_function(
    name: StringId,
    parameters: u32,
    frame_size: u32,
    body: Vec<u8>,
) -> EditedFunction {
    EditedFunction::new(
        FunctionHeader {
            offset: 0,
            parameters,
            loop_depth: 0,
            size: body.len() as u32,
            name: name.0,
            number_regs: 0,
            non_pointer_regs: 0,
            frame_size,
            read_cache: 0,
            write_cache: 0,
            object_cache: 0,
            private_cache: 0,
            flags: FunctionFlags {
                prohibit: Prohibit::Construct,
                strict: true,
                exception_handler: false,
                debug_info: false,
                kind: FunctionKind::Normal,
            },
            large: None,
        },
        FunctionBody::Owned(body),
        Vec::new(),
    )
}

fn at(inst: &Instruction) -> usize {
    inst.offset.expect("decoded instructions have offsets") as usize
}

fn checked_id(value: u64, count: u32, base: u32) -> Result<u32> {
    if value >= u64::from(count) {
        return Err(invalid(0, "linked ID out of range"));
    }
    base.checked_add(value as u32)
        .ok_or_else(|| invalid(0, "linked ID overflow"))
}

/// The value type of a run in a literal buffer (`SerializedLiteralGenerator` tags).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Literal {
    Null,
    True,
    False,
    Number,
    LongString,
    ShortString,
    Undefined,
    Integer,
}

impl Literal {
    const MASK: u8 = 0x70;
    /// Set when the run length continues into a second byte.
    const LONG_RUN: u8 = 0x80;
    const LENGTH: u8 = 0x0f;

    fn decode(tag: u8) -> Self {
        match (tag & Self::MASK) >> 4 {
            0 => Self::Null,
            1 => Self::True,
            2 => Self::False,
            3 => Self::Number,
            4 => Self::LongString,
            5 => Self::ShortString,
            6 => Self::Undefined,
            _ => Self::Integer,
        }
    }

    const fn tag(self) -> u8 {
        (self as u8) << 4
    }

    const fn size(self) -> usize {
        match self {
            Self::Null | Self::True | Self::False | Self::Undefined => 0,
            Self::ShortString => 2,
            Self::LongString | Self::Integer => 4,
            Self::Number => 8,
        }
    }
}

/// How many literals an instruction reads from the value buffer: an array's
/// static elements, or one value per property of an object's shape.
fn literal_count(inst: &Instruction, shapes: &[[u8; 8]]) -> Result<Option<u32>> {
    let shape = |index: u64| {
        shapes
            .get(index as usize)
            .map(|entry| read_u32(entry, 4))
            .ok_or_else(|| invalid(at(inst), "shape ID out of range"))?
    };
    Ok(match inst.op {
        Op::NewArrayWithBuffer | Op::NewArrayWithBufferLong => Some(inst.values[2] as u32),
        Op::NewObjectWithBuffer | Op::NewObjectWithBufferLong => Some(shape(inst.values[1])?),
        Op::NewObjectWithBufferAndParent => Some(shape(inst.values[2])?),
        _ => None,
    })
}

/// A linked module's literal buffer, copied per reference. Hermes shares
/// byte-identical literal sequences, so a reference may start inside another
/// run; each one is read from its own offset and length, as the runtime reads
/// it, and copied with its string IDs remapped. Short strings become long
/// strings, since remapped IDs need not fit 16 bits.
struct Literals<'a> {
    source: &'a [u8],
    strings: &'a [u32],
    base: u32,
    output: Vec<u8>,
    copies: BTreeMap<(u32, u32), u32>,
}

impl<'a> Literals<'a> {
    fn new(source: &'a [u8], strings: &'a [u32], base: u32) -> Self {
        Self {
            source,
            strings,
            base,
            output: Vec::new(),
            copies: BTreeMap::new(),
        }
    }

    /// The offset of the copy of `count` literals read from `offset`.
    fn relocate(&mut self, offset: u32, count: u32) -> Result<u32> {
        if let Some(&copy) = self.copies.get(&(offset, count)) {
            return Ok(copy);
        }
        let copy = u32::try_from(self.output.len())
            .ok()
            .and_then(|length| self.base.checked_add(length))
            .ok_or_else(|| invalid(offset as usize, "literal buffer offset overflow"))?;
        let literals = read_literals(self.source, offset as usize, count as usize)?;
        for run in literals.chunk_by(|a, b| a.kind == b.kind) {
            for run in run.chunks(MAX_RUN) {
                let kind = match run[0].kind {
                    Literal::ShortString => Literal::LongString,
                    kind => kind,
                };
                if run.len() > usize::from(Literal::LENGTH) {
                    self.output
                        .push(Literal::LONG_RUN | kind.tag() | (run.len() >> 8) as u8);
                    self.output.push(run.len() as u8);
                } else {
                    self.output.push(kind.tag() | run.len() as u8);
                }
                for literal in run {
                    match literal.kind {
                        Literal::ShortString | Literal::LongString => {
                            let id = self.strings.get(literal.string_id()? as usize).ok_or_else(
                                || invalid(offset as usize, "literal string ID out of range"),
                            )?;
                            self.output.extend_from_slice(&id.to_le_bytes());
                        }
                        _ => self.output.extend_from_slice(literal.bytes),
                    }
                }
            }
        }
        self.copies.insert((offset, count), copy);
        Ok(copy)
    }
}

/// The longest run a tag and its second length byte can describe.
const MAX_RUN: usize = 0xfff;

/// One literal of a buffer: its kind and its encoded value.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LiteralValue<'a> {
    kind: Literal,
    bytes: &'a [u8],
}

impl LiteralValue<'_> {
    fn string_id(&self) -> Result<u32> {
        match self.kind {
            Literal::ShortString => Ok(u32::from(u16::from_le_bytes([
                self.bytes[0],
                self.bytes[1],
            ]))),
            Literal::LongString => read_u32(self.bytes, 0),
            _ => Err(invalid(0, "literal is not a string")),
        }
    }
}

/// Reads `count` literals from `offset` the way the runtime does: run by run,
/// stopping inside a run once `count` literals are read.
fn read_literals(source: &[u8], offset: usize, count: usize) -> Result<Vec<LiteralValue<'_>>> {
    let mut literals = Vec::with_capacity(count);
    let mut cursor = offset;
    while literals.len() < count {
        let tag = slice(source, cursor, 1)?[0];
        cursor += 1;
        let mut length = usize::from(tag & Literal::LENGTH);
        if tag & Literal::LONG_RUN != 0 {
            length = length << 8 | usize::from(slice(source, cursor, 1)?[0]);
            cursor += 1;
        }
        if length == 0 {
            return Err(invalid(cursor, "empty literal run"));
        }
        let kind = Literal::decode(tag);
        for _ in 0..length.min(count - literals.len()) {
            literals.push(LiteralValue {
                kind,
                bytes: slice(source, cursor, kind.size())?,
            });
            cursor += kind.size();
        }
    }
    Ok(literals)
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;

    use reseam_storage::Bytes;

    use super::*;
    use crate::{FunctionId, HermesImage};

    /// A literal as text and bytes, comparable across files whose string IDs differ.
    #[derive(Debug, PartialEq)]
    enum Value {
        Text(Vec<u16>),
        Other(Literal, Vec<u8>),
    }

    fn values(file: &HermesFile<'_>, buffer: Section, offset: u64, count: u32) -> Vec<Value> {
        read_literals(file.section(buffer), offset as usize, count as usize)
            .expect("fixture literals decode")
            .into_iter()
            .map(|literal| match literal.kind {
                Literal::ShortString | Literal::LongString => Value::Text(
                    file.string(StringId(literal.string_id().expect("string literal")))
                        .expect("literal string exists")
                        .units()
                        .collect(),
                ),
                kind => Value::Other(kind, literal.bytes.to_vec()),
            })
            .collect()
    }

    fn operand(inst: &Instruction, kind: IdKind) -> Option<u64> {
        inst.operands()
            .find(|(operand, _)| operand.id == kind)
            .map(|(_, value)| value)
    }

    /// Offsets where a run starts when the buffer is read from its beginning.
    fn run_starts(buffer: &[u8]) -> BTreeSet<u64> {
        let mut starts = BTreeSet::new();
        let mut cursor = 0;
        while cursor < buffer.len() {
            starts.insert(cursor as u64);
            let tag = buffer[cursor];
            let mut length = usize::from(tag & Literal::LENGTH);
            let mut header = 1;
            if tag & Literal::LONG_RUN != 0 {
                length = length << 8 | usize::from(buffer[cursor + 1]);
                header = 2;
            }
            cursor += header + length * Literal::decode(tag).size();
        }
        starts
    }

    #[test]
    fn linked_literals_read_back_the_values_and_keys_of_the_module() {
        let host = HermesImage::parse(Bytes::from_slice(include_bytes!("../fixtures/host.hbc")))
            .expect("host fixture parses");
        let module = HermesImage::parse(Bytes::from_slice(include_bytes!(
            "../fixtures/literals.hbc"
        )))
        .expect("module fixture parses");
        let module_file = module.file();
        let host_functions = host.file().function_count();
        let mut editor = Editor::new(host.file());
        editor.link(&module_file).expect("module links");
        let mut output = Vec::new();
        editor.write(&mut output).expect("linked file writes");
        let linked = HermesImage::parse(Bytes::from_vec(output)).expect("linked file parses");
        let linked_file = linked.file();

        let source_shapes = module_file.section(Section::Shapes).as_chunks::<8>().0;
        let linked_shapes = linked_file.section(Section::Shapes).as_chunks::<8>().0;
        let starts = run_starts(module_file.section(Section::Values));
        let mut inside_a_run = false;
        for id in 0..module_file.function_count() {
            let source = module_file
                .function(FunctionId(id))
                .unwrap()
                .instructions()
                .unwrap();
            let copy = linked_file
                .function(FunctionId(host_functions + id))
                .unwrap()
                .instructions()
                .unwrap();
            for (source, copy) in source.iter().zip(&copy) {
                let Some(count) = literal_count(source, source_shapes).unwrap() else {
                    continue;
                };
                let (from, to) = (
                    operand(source, IdKind::ValueBuffer).unwrap(),
                    operand(copy, IdKind::ValueBuffer).unwrap(),
                );
                inside_a_run |= !starts.contains(&from);
                assert_eq!(
                    values(&module_file, Section::Values, from, count),
                    values(&linked_file, Section::Values, to, count),
                    "values of {:?} in function {id}",
                    source.op
                );
                if let (Some(from), Some(to)) =
                    (operand(source, IdKind::Shape), operand(copy, IdKind::Shape))
                {
                    let key = |shapes: &[[u8; 8]], index: u64| {
                        read_u32(&shapes[index as usize], 0).unwrap()
                    };
                    assert_eq!(
                        values(
                            &module_file,
                            Section::Keys,
                            u64::from(key(source_shapes, from)),
                            count
                        ),
                        values(
                            &linked_file,
                            Section::Keys,
                            u64::from(key(linked_shapes, to)),
                            count
                        ),
                        "keys of {:?} in function {id}",
                        source.op
                    );
                }
            }
        }
        assert!(
            inside_a_run,
            "the fixture shares a literal sequence inside another run"
        );
    }
}
