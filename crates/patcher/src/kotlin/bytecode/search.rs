// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use crate::kotlin::handles::{checked, record_failure};
use boltffi::export;
use reseam_apk::reseam_dex::Instruction as DexInsn;

use crate::context::{InstructionLocation, MethodRefQuery, SiteHit};
use crate::error::{PatcherError, Result as PatcherResult};
use crate::kotlin::convert::dex_to_kotlin;
use crate::kotlin::handles::{
    alloc_method, alloc_methods, method_location, with_code, with_ctx, with_instruction,
};
use crate::kotlin::types::{
    FieldRef, Instruction, InstructionHit, MethodCallSiteResult, MethodRef, SimpleInsn,
};

#[export]
pub fn get_instructions(m: u32) -> Vec<Instruction> {
    with_code(m, |dex, code| {
        let dex_index = method_location(m)?.dex_idx;
        Some(checked(
            code.instructions()
                .iter()
                .map(|insn| dex_to_kotlin(insn, dex, Some(dex_index)))
                .collect::<reseam_apk::reseam_dex::Result<Vec<_>>>(),
        ))
    })
    .unwrap_or_default()
}

#[export]
pub fn method_references(m: u32) -> Vec<MethodRef> {
    with_code(m, |dex, code| {
        let mut seen = rustc_hash::FxHashSet::default();
        Some(
            code.instructions()
                .iter()
                .filter_map(DexInsn::method_ref)
                .filter(|reference| seen.insert(*reference))
                .map(|reference| crate::kotlin::convert::resolve_method_ref(dex, reference))
                .collect(),
        )
    })
    .unwrap_or_default()
}

#[export]
pub fn find_methods_by_string_prefix(prefix: String) -> Vec<u32> {
    alloc_methods(with_ctx(|ctx| {
        checked(ctx.find_methods_by_string_prefix(&prefix))
    }))
}

#[export]
pub fn get_instruction(m: u32, index: u32) -> Instruction {
    with_instruction(m, index, |dex, instruction| {
        let dex_index = method_location(m)?.dex_idx;
        checked(dex_to_kotlin(instruction, dex, Some(dex_index)).map(Some))
    })
    .unwrap_or_else(|| {
        record_failure(format!("method {m} has no instruction at index {index}"));
        Instruction::Simple(SimpleInsn { opcode: 0 })
    })
}

#[export]
pub fn instruction_count(m: u32) -> u32 {
    with_code(m, |_, code| Some(code.instructions().len() as u32)).unwrap_or(0)
}

#[export]
pub fn count_following_check_cast(
    sites: Vec<InstructionHit>,
    type_descriptor: String,
    look_ahead: u32,
) -> u32 {
    sites
        .iter()
        .filter(|site| {
            with_code(site.method, |dex, code| {
                Some(
                    code.instructions()
                        .iter()
                        .skip((site.index as usize).saturating_add(1))
                        .take(look_ahead as usize)
                        .any(|instruction| {
                            matches!(instruction, DexInsn::CheckCast { type_, .. }
                                if dex.type_descriptor(*type_) == type_descriptor)
                        }),
                )
            })
            .unwrap_or(false)
        })
        .count() as u32
}

#[export]
pub fn find_instructions_by_literal(literal: i64) -> Vec<InstructionHit> {
    hits(with_ctx(|ctx| {
        checked(ctx.find_instructions_by_literal(literal))
    }))
}

#[export]
pub fn find_instructions_by_string(s: String) -> Vec<InstructionHit> {
    hits(with_ctx(|ctx| checked(ctx.find_instructions_by_string(&s))))
}

#[export]
pub fn find_instructions_by_string_contains(substring: String) -> Vec<InstructionHit> {
    hits(with_ctx(|ctx| {
        checked(ctx.find_instructions_by_string_contains(&substring))
    }))
}

#[export]
pub fn find_instructions_by_resource_id(res_type: String, res_name: String) -> Vec<InstructionHit> {
    match with_ctx(|ctx| checked(ctx.apk_mut().find_resource(&res_type, &res_name))) {
        Some((_, res_id)) => find_instructions_by_literal(i64::from(res_id)),
        None => Vec::new(),
    }
}

#[export]
pub fn find_method_call_sites(
    class_names: Vec<String>,
    method_names: Vec<String>,
) -> Vec<MethodCallSiteResult> {
    site_results(with_ctx(|ctx| {
        checked(
            member_queries(class_names, method_names)
                .and_then(|targets| ctx.find_method_call_sites(&targets)),
        )
    }))
}

#[export]
pub fn find_field_access_sites(
    class_names: Vec<String>,
    field_names: Vec<String>,
) -> Vec<MethodCallSiteResult> {
    site_results(with_ctx(|ctx| {
        checked(
            member_queries(class_names, field_names)
                .and_then(|targets| ctx.find_field_access_sites(&targets)),
        )
    }))
}

fn member_queries(owners: Vec<String>, names: Vec<String>) -> PatcherResult<Vec<(String, String)>> {
    if owners.len() != names.len() {
        return Err(PatcherError::Bridge(format!(
            "member query has {} owners but {} names",
            owners.len(),
            names.len()
        )));
    }
    Ok(owners.into_iter().zip(names).collect())
}

#[export]
pub fn find_instructions_by_invoke(
    defining_class: String,
    method_name: String,
) -> Vec<InstructionHit> {
    find_method_call_sites(vec![defining_class], vec![method_name])
        .into_iter()
        .map(|site| InstructionHit {
            method: site.method,
            index: site.index,
        })
        .collect()
}

#[export]
pub fn all_method_handles() -> Vec<u32> {
    let mut locations = Vec::new();
    with_ctx(|ctx| checked(ctx.for_each_method(|location| locations.push(location))));
    alloc_methods(locations)
}

#[export]
pub fn instruction_string_ref(m: u32, index: u32) -> Option<String> {
    with_instruction(m, index, |dex, instruction| {
        let idx = instruction.string_ref()?;
        Some(dex.string(idx).into_owned())
    })
}

#[export]
pub fn instruction_method_ref(m: u32, index: u32) -> Option<MethodRef> {
    with_instruction(m, index, |dex, instruction| {
        let method = dex.method_id(instruction.method_ref()?);
        Some(MethodRef {
            defining_class: dex.type_descriptor(method.class).into_owned(),
            name: dex.string(method.name).into_owned(),
            proto: dex.proto_descriptor(&dex.proto(method.proto)),
        })
    })
}

#[export]
pub fn instruction_field_ref(m: u32, index: u32) -> Option<FieldRef> {
    with_instruction(m, index, |dex, instruction| {
        let field = dex.field_id(instruction.field_ref()?);
        Some(FieldRef {
            defining_class: dex.type_descriptor(field.class).into_owned(),
            name: dex.string(field.name).into_owned(),
            field_type: dex.type_descriptor(field.type_).into_owned(),
        })
    })
}

#[export]
pub fn instruction_type_ref(m: u32, index: u32) -> Option<String> {
    with_instruction(m, index, |dex, instruction| {
        let idx = instruction.type_ref()?;
        Some(dex.type_descriptor(idx).into_owned())
    })
}

fn hits(locations: Vec<InstructionLocation>) -> Vec<InstructionHit> {
    locations
        .into_iter()
        .map(|loc| InstructionHit {
            method: alloc_method(loc.method),
            index: loc.insn_idx as u32,
        })
        .collect()
}

fn site_results(sites: Vec<SiteHit>) -> Vec<MethodCallSiteResult> {
    sites
        .into_iter()
        .map(|hit| MethodCallSiteResult {
            method: alloc_method(hit.loc.method),
            index: hit.loc.insn_idx as u32,
            target_index: hit.target_index as u32,
        })
        .collect()
}

#[export]
pub fn find_calls_matching(
    owner: Option<String>,
    name: Option<String>,
    return_type: Option<String>,
    parameters: Option<Vec<String>>,
    required_parameters: Vec<String>,
    parameter_count: Option<u32>,
) -> Vec<InstructionHit> {
    let parameters: Option<Vec<&str>> = parameters
        .as_ref()
        .map(|v| v.iter().map(String::as_str).collect());
    let required_parameters: Vec<&str> = required_parameters.iter().map(String::as_str).collect();
    hits(with_ctx(|ctx| {
        checked(ctx.find_calls_matching(&MethodRefQuery {
            owner: owner.as_deref(),
            name: name.as_deref(),
            return_type: return_type.as_deref(),
            parameters: parameters.as_deref(),
            required_parameters: &required_parameters,
            parameter_count: parameter_count.map(|count| count as usize),
        }))
    }))
}
