// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use crate::dataflow::register_states;
use crate::edit::Editor;
use crate::error::{HermesError, Result, invalid};
use crate::model::StringId;
use crate::opcode::Op;
use crate::{FunctionId, FunctionIndex, HermesFile};

/// Metro's module registration global, `__d(factory, moduleId, dependencyMap)`.
const DEFINE: &str = "__d";

/// A module ID that Metro's `__r` accepts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct MetroModuleId(pub u32);

/// The Metro modules the app's global code defines, by factory function.
pub(crate) struct MetroModules {
    factories: BTreeMap<FunctionId, MetroModuleId>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Known {
    Define,
    Closure(FunctionId),
    Id(MetroModuleId),
}

impl MetroModules {
    /// Reads every `__d` call in the global function. A call whose factory or module ID is not a
    /// value the analysis can prove fails, so no module is silently left out.
    pub(crate) fn build(file: &HermesFile<'_>) -> Result<Self> {
        let global = file.function(file.global_function())?;
        let code = global.instructions()?;
        let states = register_states(&global, &code, |inst, before| {
            let v = &inst.values;
            Ok(match inst.op {
                Op::TryGetById | Op::TryGetByIdLong
                    if file.string(StringId(v[3] as u32))?.equals(DEFINE) =>
                {
                    Some(Known::Define)
                }
                Op::CreateClosure | Op::CreateClosureLongIndex => {
                    Some(Known::Closure(FunctionId(v[2] as u32)))
                }
                Op::LoadConstZero => Some(Known::Id(MetroModuleId(0))),
                Op::LoadConstUInt8 => Some(Known::Id(MetroModuleId(v[1] as u32))),
                Op::LoadConstInt => u32::try_from(v[1] as u32 as i32)
                    .ok()
                    .map(|id| Known::Id(MetroModuleId(id))),
                Op::Mov | Op::MovLong => before.get(&(v[1] as u32)).copied(),
                _ => None,
            })
        })?;
        let mut factories = BTreeMap::new();
        for (index, inst) in code.iter().enumerate() {
            let Some(state) = states.get(&index) else {
                continue;
            };
            if inst.op != Op::Call4 || state.get(&(inst.values[1] as u32)) != Some(&Known::Define) {
                continue;
            }
            let offset = inst.offset.expect("analysed instructions are decoded") as usize;
            let (Some(&Known::Closure(factory)), Some(&Known::Id(id))) = (
                state.get(&(inst.values[3] as u32)),
                state.get(&(inst.values[4] as u32)),
            ) else {
                return Err(invalid(
                    offset,
                    "Metro module definition without a provable factory and ID",
                ));
            };
            if factories
                .insert(factory, id)
                .is_some_and(|previous| previous != id)
            {
                return Err(invalid(offset, "Metro factory defines several modules"));
            }
        }
        if factories.is_empty() {
            return Err(HermesError::Unsupported(
                "the global code defines no Metro modules".into(),
            ));
        }
        Ok(Self { factories })
    }
}

impl Editor<'_> {
    /// The Metro module whose factory creates `function`, directly or through enclosing
    /// functions. A function created outside every module, or inside several, fails.
    pub(crate) fn metro_module(
        &mut self,
        index: &FunctionIndex,
        function: FunctionId,
    ) -> Result<MetroModuleId> {
        if self.edits.metro.is_none() {
            self.edits.metro = Some(MetroModules::build(&self.file)?);
        }
        let global = self.file.global_function();
        let mut modules = BTreeSet::new();
        let mut seen = BTreeSet::from([function]);
        let mut pending = VecDeque::from([function]);
        while let Some(id) = pending.pop_front() {
            let factories = &self
                .edits
                .metro
                .as_ref()
                .expect("Metro modules built")
                .factories;
            if let Some(&module) = factories.get(&id) {
                modules.insert(module);
                continue;
            }
            if id == global {
                return Err(HermesError::Unsupported(format!(
                    "function {} is created outside every Metro module",
                    function.0
                )));
            }
            for &creator in index.creators(id) {
                if seen.insert(creator) {
                    pending.push_back(creator);
                }
            }
        }
        match modules.into_iter().collect::<Vec<_>>()[..] {
            [module] => Ok(module),
            [] => Err(HermesError::Unsupported(format!(
                "function {} is not reachable from a Metro module",
                function.0
            ))),
            _ => Err(HermesError::Unsupported(format!(
                "function {} is created in several Metro modules",
                function.0
            ))),
        }
    }
}

#[cfg(test)]
mod tests {
    use reseam_storage::Bytes;

    use super::*;
    use crate::HermesImage;

    #[test]
    fn functions_resolve_to_the_metro_module_whose_factory_creates_them() {
        let host = HermesImage::parse(Bytes::from_slice(include_bytes!("../fixtures/host.hbc")))
            .expect("host fixture parses");
        let index = FunctionIndex::build(&host.file()).expect("host indexes");
        let function = |name| {
            index
                .find_function(&host.file(), Some(name), &[], None)
                .expect("fixture function exists")
        };
        let mut editor = Editor::new(host.file());
        assert_eq!(
            editor.metro_module(&index, function("greet")).unwrap(),
            MetroModuleId(7)
        );
        assert_eq!(
            editor.metro_module(&index, function("getCount")).unwrap(),
            MetroModuleId(300)
        );
        assert!(
            editor
                .metro_module(&index, host.file().global_function())
                .is_err()
        );
    }
}
