// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

mod instructions;
mod methods;

use super::{FingerprintLocation, InstructionLocation, MethodLocation, PatchContext};
use crate::error::Result;
use reseam_apk::reseam_dex::{
    DexFile, FingerprintHit, InstructionSite, MethodHit, StringIdx, TypeIdx,
};
use std::collections::HashMap;
use std::hash::Hash;
use tracing::debug;

type DexResult<T> = reseam_apk::reseam_dex::Result<T>;

impl PatchContext<'_> {
    pub(super) fn scan_all<T>(
        &self,
        what: &str,
        scan: impl Fn(usize, &DexFile) -> DexResult<Vec<T>>,
    ) -> Result<Vec<T>> {
        debug!(what, "full scan");
        self.dex()
            .iter()
            .enumerate()
            .try_fold(Vec::new(), |mut hits, (index, dex)| {
                hits.extend(scan(index, dex)?);
                Ok(hits)
            })
    }

    pub(super) fn scan_first<T>(
        &self,
        what: &str,
        scan: impl Fn(usize, &DexFile) -> DexResult<Option<T>>,
    ) -> Result<Option<T>> {
        debug!(what, "scan");
        for (index, dex) in self.dex().iter().enumerate() {
            if let Some(hit) = scan(index, dex)? {
                return Ok(Some(hit));
            }
        }
        Ok(None)
    }
}

/// A method reference to find calls to. Unset parts match anything;
/// `required_parameters` must each appear somewhere in the parameter list.
#[derive(Debug, Clone, Copy, Default)]
pub struct MethodRefQuery<'a> {
    pub owner: Option<&'a str>,
    pub name: Option<&'a str>,
    pub return_type: Option<&'a str>,
    pub parameters: Option<&'a [&'a str]>,
    pub required_parameters: &'a [&'a str],
    pub parameter_count: Option<usize>,
}

impl MethodRefQuery<'_> {
    pub(super) fn constrains_proto(&self) -> bool {
        self.return_type.is_some()
            || self.parameters.is_some()
            || !self.required_parameters.is_empty()
            || self.parameter_count.is_some()
    }
}

/// A field reference to find reads and writes of. Unset parts match anything.
pub struct FieldRefQuery<'a> {
    pub owner: Option<&'a str>,
    pub name: Option<&'a str>,
    pub field_type: Option<&'a str>,
}

pub(super) struct Unresolved;

pub(super) fn resolve<T>(
    value: Option<&str>,
    find: impl FnOnce(&str) -> Option<T>,
) -> std::result::Result<Option<T>, Unresolved> {
    value.map_or(Ok(None), |value| find(value).map(Some).ok_or(Unresolved))
}

pub(super) fn member_targets<K: Hash + Eq, I: Iterator<Item = K>>(
    dex: &DexFile,
    targets: &[(String, String)],
    members: impl Fn(TypeIdx, StringIdx) -> I,
) -> HashMap<K, usize> {
    targets
        .iter()
        .enumerate()
        .filter_map(|(index, (class, name))| {
            Some((index, dex.find_type_idx(class)?, dex.find_string_idx(name)?))
        })
        .flat_map(|(index, class, name)| members(class, name).map(move |id| (id, index)))
        .fold(HashMap::new(), |mut map, (id, index)| {
            map.entry(id).or_insert(index);
            map
        })
}

pub(super) fn method_location(dex_idx: usize, hit: &MethodHit) -> MethodLocation {
    MethodLocation {
        dex_idx,
        class_idx: hit.class_idx,
        method_idx: hit.method_pos,
        kind: hit.kind,
    }
}

pub(super) fn fingerprint_location(dex_idx: usize, hit: FingerprintHit) -> FingerprintLocation {
    FingerprintLocation {
        method: method_location(dex_idx, &hit.method),
        matched_indices: hit.matched_indices,
    }
}

pub(super) fn instruction_location(
    dex_idx: usize,
    site: &InstructionSite<'_>,
) -> InstructionLocation {
    InstructionLocation {
        method: MethodLocation {
            dex_idx,
            class_idx: site.class_idx,
            method_idx: site.method_pos,
            kind: site.kind,
        },
        insn_idx: site.insn_idx,
    }
}
