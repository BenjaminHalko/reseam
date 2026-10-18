// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use super::{ClassTable, DexFile, FileTable, HiddenApiData, IdTable, StringPool};
use crate::types::method_handle::{CallSiteItem, MethodHandle};
use crate::types::{
    FieldId, FieldIdx, MethodId, MethodIdx, ProtoIdx, Prototype, StringIdx, TypeIdx,
};

impl DexFile {
    pub fn strings(&self) -> &StringPool {
        &self.strings
    }

    /// Marks the DEX changed before granting mutation.
    pub fn strings_mut(&mut self) -> &mut StringPool {
        self.touch();
        &mut self.strings
    }

    pub fn types(&self) -> &IdTable<StringIdx> {
        &self.types
    }

    /// Marks the DEX changed before granting mutation.
    pub fn types_mut(&mut self) -> &mut IdTable<StringIdx> {
        self.touch();
        &mut self.types
    }

    pub fn prototypes(&self) -> &IdTable<Prototype> {
        &self.prototypes
    }

    /// Marks the DEX changed before granting mutation.
    pub fn prototypes_mut(&mut self) -> &mut IdTable<Prototype> {
        self.touch();
        &mut self.prototypes
    }

    pub fn fields(&self) -> &IdTable<FieldId> {
        &self.fields
    }

    /// Marks the DEX changed before granting mutation.
    pub fn fields_mut(&mut self) -> &mut IdTable<FieldId> {
        self.touch();
        &mut self.fields
    }

    pub fn methods(&self) -> &IdTable<MethodId> {
        &self.methods
    }

    /// Marks the DEX changed before granting mutation.
    pub fn methods_mut(&mut self) -> &mut IdTable<MethodId> {
        self.touch();
        &mut self.methods
    }

    pub fn classes(&self) -> &ClassTable {
        &self.classes
    }

    /// Marks the DEX changed before granting mutation.
    pub fn classes_mut(&mut self) -> &mut ClassTable {
        self.touch();
        // The index locates methods by class position; insertion or removal can shift those.
        self.ref_index.take();
        &mut self.classes
    }

    pub fn call_sites(&self) -> &FileTable<CallSiteItem> {
        &self.call_sites
    }

    /// Marks the DEX changed before granting mutation.
    pub fn call_sites_mut(&mut self) -> &mut FileTable<CallSiteItem> {
        self.touch();
        &mut self.call_sites
    }

    pub fn method_handles(&self) -> &FileTable<MethodHandle> {
        &self.method_handles
    }

    /// Marks the DEX changed before granting mutation.
    pub fn method_handles_mut(&mut self) -> &mut FileTable<MethodHandle> {
        self.touch();
        &mut self.method_handles
    }

    pub fn hidden_api(&self) -> Option<&HiddenApiData> {
        self.hidden_api.as_ref()
    }

    /// Marks the DEX changed before granting mutation.
    pub fn hidden_api_mut(&mut self) -> &mut Option<HiddenApiData> {
        self.touch();
        self.journal.record(super::undo::DexPart::HiddenApi, || {
            super::undo::DexUndo::HiddenApi(self.hidden_api.clone())
        });
        &mut self.hidden_api
    }

    pub fn method_count(&self) -> usize {
        self.methods.len()
    }

    pub fn field_count(&self) -> usize {
        self.fields.len()
    }

    pub fn type_count(&self) -> usize {
        self.types.len()
    }

    pub fn can_add_methods(&self, count: usize) -> bool {
        self.methods.len() + count <= crate::write::MAX_POOL_SIZE
    }

    pub fn can_add_fields(&self, count: usize) -> bool {
        self.fields.len() + count <= crate::write::MAX_POOL_SIZE
    }

    pub fn can_add_types(&self, count: usize) -> bool {
        self.types.len() + count <= crate::write::MAX_POOL_SIZE
    }

    pub fn type_string(&self, idx: TypeIdx) -> StringIdx {
        self.types.get(idx.0 as usize)
    }

    pub fn proto(&self, idx: ProtoIdx) -> Prototype {
        self.prototypes.get(idx.0 as usize)
    }

    pub fn field_id(&self, idx: FieldIdx) -> FieldId {
        self.fields.get(idx.0 as usize)
    }

    pub fn method_id(&self, idx: MethodIdx) -> MethodId {
        self.methods.get(idx.0 as usize)
    }
}
