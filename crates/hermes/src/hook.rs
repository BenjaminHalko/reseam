// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use crate::code::{Code, Condition, HookKind, Operand, Place, SlotId, Statement};
use crate::codegen::{self, Layer, Links};
use crate::edit::Editor;
use crate::environment::original_function;
use crate::error::{HermesError, Result, invalid};
use crate::model::{StringId, StringKind};
use crate::{FunctionId, FunctionIndex, ModuleId};

impl Editor<'_> {
    /// Adds `code` to `function` as a new layer; callers are untouched. [`HookKind::Before`] and
    /// [`HookKind::After`] run around the function as it was, which later layers then run around
    /// in turn; [`HookKind::Replace`] runs instead of it, and earlier layers no longer run. The
    /// receiver and the linked modules' exports keep the function's original captured
    /// environment.
    ///
    /// Generators, async functions, constructors, functions using `new.target` or direct
    /// `eval`, unknown exports, malformed code and unprovable environment chains fail. A replaced
    /// function whose closures an earlier hook needs fails too. Hooked functions cannot be used
    /// as constructors afterwards.
    pub fn hook(
        &mut self,
        index: &FunctionIndex,
        function: FunctionId,
        kind: HookKind,
        code: &Code,
    ) -> Result<()> {
        index.check(&self.file)?;
        self.transaction(|editor| editor.hook_function(index, function, kind, code))
    }

    fn hook_function(
        &mut self,
        index: &FunctionIndex,
        function: FunctionId,
        kind: HookKind,
        code: &Code,
    ) -> Result<()> {
        let target = self.editable(function)?;
        let mut resolver = Resolver {
            editor: self,
            kind,
            slots: 0,
            calls_exports: false,
        };
        let code = resolver.code(code)?;
        let (slots, calls_exports) = (resolver.slots, resolver.calls_exports);
        let plan = if calls_exports {
            Some(self.hook_plan(index, function)?)
        } else {
            None
        };
        if let Some(id) = plan
            .iter()
            .flat_map(|plan| plan.roots.keys())
            .find(|id| self.edits.discarded.contains(id) && !self.edits.relocated.contains_key(id))
        {
            return Err(HermesError::Unsupported(format!(
                "function {} was replaced, so the closures this hook needs no longer exist",
                id.0
            )));
        }
        let edited = self.edits.functions.contains_key(&function);
        let original = (kind != HookKind::Replace).then(|| self.next_function());
        let layer = Layer { kind, code, slots };
        let links = Links {
            original,
            modules: plan.as_ref().map(|plan| plan.depth),
        };
        let body = codegen::compile(&target, &layer, &links)?;
        if let Some(original) = original {
            let previous = if edited {
                self.edits.functions[&function].clone()
            } else {
                original_function(&target)?
            };
            self.append_function(previous);
            if !edited {
                self.edits.relocated.insert(function, original);
            }
        } else if !edited {
            if self.edits.roots.contains_key(&function) {
                return Err(HermesError::Unsupported(format!(
                    "function {} creates closures that a hook needs",
                    function.0
                )));
            }
            self.edits.discarded.insert(function);
        }
        self.edits.functions.insert(function, body);
        for (id, root) in plan.into_iter().flat_map(|plan| plan.roots) {
            self.edits
                .roots
                .entry(id)
                .and_modify(|existing| existing.sites.extend(&root.sites))
                .or_insert(root);
        }
        Ok(())
    }

    fn declared_export(&mut self, module: ModuleId, export: &str) -> Result<StringId> {
        let linked = self
            .edits
            .modules
            .get(module.0 as usize)
            .ok_or_else(|| invalid(0, "module belongs to another editor"))?;
        let declared = linked.exports.iter().any(|id| {
            self.edits.strings[(id.0 - self.file.string_count()) as usize]
                .value()
                .equals(export)
        });
        if !declared {
            return Err(HermesError::Unsupported(format!(
                "module does not export {export:?}"
            )));
        }
        self.intern(export, StringKind::Identifier)
    }
}

/// Checks a hook's code and resolves its names to the file's strings.
struct Resolver<'e, 'a> {
    editor: &'e mut Editor<'a>,
    kind: HookKind,
    slots: u32,
    calls_exports: bool,
}

impl Resolver<'_, '_> {
    fn code(&mut self, code: &Code) -> Result<Code<StringId>> {
        if code.blocks.is_empty() {
            return Err(malformed("hook code has no body"));
        }
        let mut entered = vec![false; code.blocks.len()];
        entered[0] = true;
        for statement in code.blocks.iter().flatten() {
            if let Statement::Branch {
                then, otherwise, ..
            } = statement
            {
                for block in [then, otherwise] {
                    let entry = entered
                        .get_mut(block.0 as usize)
                        .ok_or_else(|| malformed("branch to a missing block"))?;
                    if std::mem::replace(entry, true) {
                        return Err(malformed("a block runs from more than one place"));
                    }
                }
            }
        }
        let blocks = code
            .blocks
            .iter()
            .map(|block| block.iter().map(|s| self.statement(s)).collect())
            .collect::<Result<_>>()?;
        Ok(Code { blocks })
    }

    fn statement(&mut self, statement: &Statement) -> Result<Statement<StringId>> {
        Ok(match statement {
            Statement::Call {
                into,
                module,
                export,
                arguments,
            } => {
                self.slot(*into);
                self.calls_exports = true;
                Statement::Call {
                    into: *into,
                    module: *module,
                    export: self.editor.declared_export(*module, export)?,
                    arguments: arguments
                        .iter()
                        .map(|argument| self.operand(argument))
                        .collect::<Result<_>>()?,
                }
            }
            Statement::Assign { place, value } => {
                match place {
                    Place::Result => self.require_result()?,
                    Place::Slot(slot) => self.slot(*slot),
                    Place::Param(_) => {}
                }
                Statement::Assign {
                    place: *place,
                    value: self.operand(value)?,
                }
            }
            Statement::Return(value) => Statement::Return(self.operand(value)?),
            Statement::Branch {
                condition,
                then,
                otherwise,
            } => Statement::Branch {
                condition: match condition {
                    Condition::True(value) => Condition::True(self.operand(value)?),
                    Condition::False(value) => Condition::False(self.operand(value)?),
                    Condition::Null(value) => Condition::Null(self.operand(value)?),
                    Condition::NotNull(value) => Condition::NotNull(self.operand(value)?),
                    Condition::Equal(left, right) => {
                        Condition::Equal(self.operand(left)?, self.operand(right)?)
                    }
                    Condition::NotEqual(left, right) => {
                        Condition::NotEqual(self.operand(left)?, self.operand(right)?)
                    }
                },
                then: *then,
                otherwise: *otherwise,
            },
        })
    }

    fn operand(&mut self, operand: &Operand) -> Result<Operand<StringId>> {
        Ok(match operand {
            Operand::Receiver => Operand::Receiver,
            Operand::Param(index) => Operand::Param(*index),
            Operand::Result => {
                self.require_result()?;
                Operand::Result
            }
            Operand::Slot(slot) => {
                self.slot(*slot);
                Operand::Slot(*slot)
            }
            Operand::Undefined => Operand::Undefined,
            Operand::Null => Operand::Null,
            Operand::Bool(value) => Operand::Bool(*value),
            Operand::Int(value) => Operand::Int(*value),
            Operand::Text(text) => Operand::Text(self.editor.intern(text, StringKind::String)?),
        })
    }

    fn slot(&mut self, slot: SlotId) {
        self.slots = self.slots.max(slot.0 + 1);
    }

    fn require_result(&self) -> Result<()> {
        if self.kind == HookKind::After {
            Ok(())
        } else {
            Err(malformed("only after hooks have a result"))
        }
    }
}

fn malformed(reason: &str) -> HermesError {
    HermesError::Unsupported(format!("malformed hook code: {reason}"))
}

#[cfg(test)]
mod tests {
    use reseam_storage::Bytes;

    use crate::code::{BlockId, Code, Condition, HookKind, Operand, Place, SlotId, Statement};
    use crate::{Editor, FunctionIndex, HermesImage};

    /// Code that reads every kind of operand, branches and calls an export.
    fn exercising(module: crate::ModuleId, kind: HookKind) -> Code {
        let result = if kind == HookKind::After {
            Operand::Result
        } else {
            Operand::Param(0)
        };
        let call = |into| Statement::Call {
            into: SlotId(into),
            module,
            export: "withProperty".into(),
            arguments: vec![
                Operand::Text("bundle".into()),
                Operand::Int(-1),
                Operand::Bool(true),
                Operand::Receiver,
                Operand::Param(2),
            ],
        };
        Code {
            blocks: vec![
                vec![
                    call(0),
                    Statement::Branch {
                        condition: Condition::NotNull(Operand::Slot(SlotId(0))),
                        then: BlockId(1),
                        otherwise: BlockId(2),
                    },
                    Statement::Assign {
                        place: Place::Param(1),
                        value: result,
                    },
                ],
                vec![Statement::Return(Operand::Slot(SlotId(0)))],
                vec![
                    call(1),
                    Statement::Branch {
                        condition: Condition::Equal(Operand::Slot(SlotId(1)), Operand::Null),
                        then: BlockId(3),
                        otherwise: BlockId(4),
                    },
                ],
                vec![Statement::Return(Operand::Undefined)],
                vec![],
            ],
        }
    }

    #[test]
    fn hooked_functions_jump_only_to_their_own_instructions() {
        let host = HermesImage::parse(Bytes::from_slice(include_bytes!("../fixtures/host.hbc")))
            .expect("host fixture parses");
        let module =
            HermesImage::parse(Bytes::from_slice(include_bytes!("../fixtures/bundled.hbc")))
                .expect("module fixture parses");
        let index = FunctionIndex::build(&host.file()).expect("host indexes");
        let greet = index
            .find_function(&host.file(), Some("greet"), &[], None)
            .expect("fixture function exists");
        let mut editor = Editor::new(host.file());
        let id = editor
            .link(&index, &module.file(), &[])
            .expect("module links");
        for kind in [HookKind::Before, HookKind::After, HookKind::Replace] {
            editor
                .hook(&index, greet, kind, &exercising(id, kind))
                .unwrap_or_else(|error| panic!("{kind:?} hook applies: {error}"));
        }
        let mut output = Vec::new();
        editor.write(&mut output).expect("hooked file writes");
        let hooked = HermesImage::parse(Bytes::from_vec(output)).expect("hooked file parses");
        let file = hooked.file();
        for id in 0..file.function_count() {
            let function = file
                .function(crate::FunctionId(id))
                .expect("function parses");
            let code = function.instructions().expect("function decodes");
            let starts: std::collections::BTreeSet<i64> = code
                .iter()
                .map(|inst| i64::from(inst.offset.expect("decoded")))
                .collect();
            let end = function.body_range().len() as i64;
            for inst in &code {
                for (operand, value) in inst.operands() {
                    if !operand.kind.is_address() {
                        continue;
                    }
                    let relative = match operand.kind.width() {
                        1 => i64::from(value as u8 as i8),
                        _ => i64::from(value as u32 as i32),
                    };
                    let target = i64::from(inst.offset.expect("decoded")) + relative;
                    assert!(
                        starts.contains(&target) || target == end,
                        "function {id} jumps from {:?} to byte {target}",
                        inst.op
                    );
                }
            }
        }
    }
}
