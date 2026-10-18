// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::HashSet;

use super::super::{InstructionLocation, PatchContext, SiteHit};
use super::{MethodRefQuery, instruction_location, member_targets};
use crate::error::Result;
use reseam_apk::reseam_dex::{MethodIdx, ProtoIdx, RefKey, RefQuery, StringIdx, TypeIdx};

impl PatchContext<'_> {
    pub fn find_instructions_by_literal(&self, literal: i64) -> Result<Vec<InstructionLocation>> {
        self.scan_all("instructions by literal", |dex_idx, dex| {
            dex.scan_instructions(&RefQuery::all_of([RefKey::Literal(literal)]), |site| {
                (site.instruction.literal() == Some(literal))
                    .then(|| instruction_location(dex_idx, site))
            })
        })
    }

    pub fn find_instructions_by_string(&self, target: &str) -> Result<Vec<InstructionLocation>> {
        self.scan_all("instructions by string", |dex_idx, dex| {
            let Some(target_idx) = dex.find_string_idx(target) else {
                return Ok(Vec::new());
            };
            dex.scan_instructions(&RefQuery::all_of([RefKey::String(target_idx)]), |site| {
                (site.instruction.string_ref() == Some(target_idx))
                    .then(|| instruction_location(dex_idx, site))
            })
        })
    }

    pub fn find_instructions_by_string_contains(
        &self,
        substring: &str,
    ) -> Result<Vec<InstructionLocation>> {
        self.scan_all("instructions by substring", |dex_idx, dex| {
            let matches: HashSet<StringIdx> = dex
                .strings()
                .iter()
                .enumerate()
                .filter(|(_, s)| s.contains(substring))
                .map(|(i, _)| StringIdx(i as u32))
                .collect();
            if matches.is_empty() {
                return Ok(Vec::new());
            }
            let query = RefQuery::any_of(matches.iter().map(|&s| RefKey::String(s)));
            dex.scan_instructions(&query, |site| {
                site.instruction
                    .string_ref()
                    .is_some_and(|sref| matches.contains(&sref))
                    .then(|| instruction_location(dex_idx, site))
            })
        })
    }

    /// Call sites of `(class, method)` targets; hits carry the target's index.
    pub fn find_method_call_sites(&self, targets: &[(String, String)]) -> Result<Vec<SiteHit>> {
        self.scan_all("method call sites", |dex_idx, dex| {
            let map = member_targets(dex, targets, |class, name| {
                dex.methods_of(class, Some(name))
            });
            if map.is_empty() {
                return Ok(Vec::new());
            }
            let query = RefQuery::any_of(map.keys().map(|&m| RefKey::Method(m)));
            dex.scan_instructions(&query, |site| {
                let target_index = *map.get(&site.instruction.method_ref()?)?;
                Some(SiteHit {
                    loc: instruction_location(dex_idx, site),
                    target_index,
                })
            })
        })
    }

    /// Match referenced method IDs before scanning their indexed call sites. Platform
    /// references are included even when their declaring class is not in the APK.
    pub fn find_calls_matching(
        &self,
        query: &MethodRefQuery<'_>,
    ) -> Result<Vec<InstructionLocation>> {
        let MethodRefQuery {
            owner,
            name,
            return_type,
            parameters,
            required_parameters,
            parameter_count,
        } = *query;
        self.scan_all("calls matching reference", |dex_idx, dex| {
            let owner = match owner {
                Some(v) => match dex.find_type_idx(v) {
                    Some(v) => Some(v),
                    None => return Ok(Vec::new()),
                },
                None => None,
            };
            let name = match name {
                Some(v) => match dex.find_string_idx(v) {
                    Some(v) => Some(v),
                    None => return Ok(Vec::new()),
                },
                None => None,
            };
            let matches_proto = |proto: ProtoIdx| {
                let proto = dex.proto(proto);
                let parameter = |t: &TypeIdx| dex.type_descriptor(*t);
                return_type.is_none_or(|v| dex.type_descriptor(proto.return_type) == v)
                    && parameters.is_none_or(|v| {
                        v.len() == proto.parameters.len()
                            && v.iter()
                                .zip(&proto.parameters)
                                .all(|(v, t)| *v == parameter(t))
                    })
                    && parameter_count.is_none_or(|count| count == proto.parameters.len())
                    && required_parameters
                        .iter()
                        .all(|v| proto.parameters.iter().any(|t| *v == parameter(t)))
            };
            // Owner/name typically select one or two IDs. Only precompute all
            // matching prototypes for a signature-only query.
            let matching_protos = if owner.is_none() && name.is_none() && query.constrains_proto() {
                Some(
                    (0..dex.prototypes().len())
                        .filter_map(|i| matches_proto(ProtoIdx(i as u32)).then_some(i as u32))
                        .collect::<HashSet<_>>(),
                )
            } else {
                None
            };
            let accepts_proto = |proto: ProtoIdx| {
                matching_protos
                    .as_ref()
                    .map_or_else(|| matches_proto(proto), |protos| protos.contains(&proto.0))
            };
            let members: HashSet<_> = match owner {
                Some(owner) => dex
                    .methods_of(owner, name)
                    .filter(|m| accepts_proto(dex.methods().get(m.0 as usize).proto))
                    .collect(),
                None => dex
                    .methods()
                    .iter()
                    .enumerate()
                    .filter_map(|(i, id)| {
                        (name.is_none_or(|v| id.name == v) && accepts_proto(id.proto))
                            .then_some(MethodIdx(i as u32))
                    })
                    .collect(),
            };
            if members.is_empty() {
                return Ok(Vec::new());
            }
            let query = RefQuery::any_of(members.iter().copied().map(RefKey::Method));
            dex.scan_instructions(&query, |site| {
                members
                    .contains(&site.instruction.method_ref()?)
                    .then(|| instruction_location(dex_idx, site))
            })
        })
    }

    pub fn find_field_access_sites(&self, targets: &[(String, String)]) -> Result<Vec<SiteHit>> {
        self.scan_all("field access sites", |dex_idx, dex| {
            let map = member_targets(dex, targets, |class, name| dex.fields_of(class, Some(name)));
            if map.is_empty() {
                return Ok(Vec::new());
            }
            let query = RefQuery::any_of(map.keys().map(|&f| RefKey::Field(f)));
            dex.scan_instructions(&query, |site| {
                let target_index = *map.get(&site.instruction.field_ref()?)?;
                Some(SiteHit {
                    loc: instruction_location(dex_idx, site),
                    target_index,
                })
            })
        })
    }
}
