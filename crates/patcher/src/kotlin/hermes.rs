// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::cell::RefCell;

use boltffi::{data, export};
use reseam_hermes::{Argument, Constant, FunctionId, ModuleId};

use super::handles::{HandleSpace, bundle_path, with_ctx_result};

#[derive(Default)]
struct Functions {
    identities: HandleSpace,
    entries: Vec<FunctionId>,
}

thread_local! { static FUNCTIONS: RefCell<Functions> = RefCell::default(); }

pub(super) fn reset() {
    FUNCTIONS.with(|f| *f.borrow_mut() = Functions::default());
}

#[export]
pub fn hermes_version() -> Result<u32, String> {
    with_ctx_result(|ctx| ctx.hermes_version().map_err(|e| e.to_string()))
}

#[export]
pub fn hermes_find_function(
    name: Option<String>,
    strings: Vec<String>,
    parameters: Option<u32>,
) -> Result<u32, String> {
    with_ctx_result(|ctx| {
        let strings: Vec<_> = strings.iter().map(String::as_str).collect();
        let id = ctx
            .hermes_find(name.as_deref(), &strings, parameters)
            .map_err(|e| e.to_string())?;
        FUNCTIONS.with(|functions| {
            let mut functions = functions.borrow_mut();
            let slot = functions.entries.len();
            let handle = functions
                .identities
                .allocate(slot)
                .map_err(|e| e.to_string())?;
            functions.entries.push(id);
            Ok(handle)
        })
    })
}

fn function(handle: u32) -> Result<FunctionId, String> {
    FUNCTIONS.with(|functions| {
        let functions = functions.borrow();
        let slot = functions
            .identities
            .slot(handle)
            .ok_or("stale Hermes function handle")?;
        functions
            .entries
            .get(slot)
            .copied()
            .ok_or_else(|| "stale Hermes function handle".to_owned())
    })
}

/// A value a replaced Hermes function returns.
#[data]
#[derive(Debug, Clone)]
pub enum HermesConstant {
    Undefined,
    Null,
    Bool(bool),
    Int(i32),
    Text(String),
}

#[export]
pub fn hermes_always_return(handle: u32, value: HermesConstant) -> Result<(), String> {
    let function = function(handle)?;
    let value = match value {
        HermesConstant::Undefined => Constant::Undefined,
        HermesConstant::Null => Constant::Null,
        HermesConstant::Bool(value) => Constant::Bool(value),
        HermesConstant::Int(value) => Constant::Int(value),
        HermesConstant::Text(text) => Constant::Text(text),
    };
    with_ctx_result(|ctx| {
        ctx.hermes_always_return(function, &value)
            .map_err(|e| e.to_string())
    })
}

/// A value bound into a wrap ahead of `original`.
#[data]
#[derive(Debug, Clone)]
pub enum HermesArgument {
    Bool(bool),
    Int(i32),
    Text(String),
    Export(HermesExportRef),
    /// The exports of the Metro module that creates the function with this handle.
    Module(u32),
}

/// A JavaScript module compiled to Hermes bytecode.
#[data]
#[derive(Debug, Clone)]
pub enum HermesModule {
    /// The app runtime's module, which the engine provides.
    AppRuntime,
    /// A JavaScript extension in the running patch's bundle.
    Bundle { name: String },
}

/// A callable export of a Hermes module.
#[data]
#[derive(Debug, Clone)]
pub struct HermesExportRef {
    pub module: HermesModule,
    pub name: String,
}

#[export]
pub fn hermes_wrap(
    handle: u32,
    module: HermesModule,
    export: String,
    bound: Vec<HermesArgument>,
) -> Result<(), String> {
    let target = function(handle)?;
    with_ctx_result(|ctx| {
        let mut link = |module: &HermesModule| -> Result<ModuleId, String> {
            let linked = match module {
                HermesModule::AppRuntime => ctx.hermes_link_app_runtime(),
                HermesModule::Bundle { name } => {
                    if name.is_empty()
                        || !name
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
                    {
                        return Err(
                            "Hermes module names contain only letters, digits, hyphens and \
                             underscores"
                                .into(),
                        );
                    }
                    let version = ctx.hermes_version().map_err(|e| e.to_string())?;
                    ctx.hermes_link(&bundle_path(&format!(
                        "resources/hermes/v{version}/{name}.hbc"
                    )))
                }
            };
            linked.map_err(|e| e.to_string())
        };
        let module = link(&module)?;
        let bound = bound
            .into_iter()
            .map(|argument| {
                Ok(match argument {
                    HermesArgument::Bool(value) => Argument::Bool(value),
                    HermesArgument::Int(value) => Argument::Int(value),
                    HermesArgument::Text(text) => Argument::Text(text),
                    HermesArgument::Export(export) => Argument::Export {
                        module: link(&export.module)?,
                        name: export.name,
                    },
                    HermesArgument::Module(handle) => Argument::Module(function(handle)?),
                })
            })
            .collect::<Result<Vec<_>, String>>()?;
        ctx.hermes_wrap(target, module, &export, &bound)
            .map_err(|e| e.to_string())
    })
}
