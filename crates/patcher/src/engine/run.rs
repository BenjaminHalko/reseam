// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::HashMap;
use std::panic::{self, AssertUnwindSafe};

use tracing::{info, info_span};

use super::plan::{CHANGES_MANIFEST, unmountable_status};
use super::{PatchResult, PatchStatus, ProgressEvent, ResolvedPlan};
use crate::context::PatchContext;
use crate::error::{PatcherError, Result};
use crate::log::LogEntry;
use crate::patch::{Patch, PatchPhase, PatchSpec};

/// Runs `plan` over `specs` in dependency order, then the `after_dependents`
/// hook of every applied patch that finalizes, then binds the app entry hook to
/// the final manifest. `patches` holds the code of at least every patch the
/// plan executes. A patch that fails or panics does not stop the run; patches
/// depending on it are skipped. Each patch is reported finished once its result
/// is final: after execution, or after finalization when it finalizes.
pub fn apply_patches(
    ctx: &mut PatchContext<'_>,
    specs: &[&PatchSpec],
    plan: &ResolvedPlan,
    patches: &[&Patch],
    mut observer: impl FnMut(ProgressEvent),
) -> Result<Vec<PatchResult>> {
    #[cfg(feature = "bridge")]
    let _bridge = crate::kotlin::handles::RunGuard::enter()?;
    info!(
        patch_count = plan.order().len(),
        "starting patch application"
    );
    let code = loaded_code(specs, plan, patches)?;
    let mut run = Run::new(specs, plan, &code);

    for &idx in plan.order() {
        let reference = specs[idx].reference();
        let _span = info_span!("patch", patch = reference.as_str()).entered();
        if let Some(status) = run.blocked(idx) {
            run.finish(idx, status, Vec::new(), &mut observer);
            continue;
        }
        let patch = code[idx].expect("loaded_code holds the code of every patch the plan executes");

        ctx.begin_patch(&reference, plan.options(idx).clone());
        observer(ProgressEvent::Started {
            patch: reference.clone(),
        });
        let manifest = ctx.apk().manifest_revision();
        let outcome = guarded(|| patch.invoke(PatchPhase::Execute, ctx));
        let logs = ctx.take_log_entries();
        for log in &logs {
            observer(ProgressEvent::Log(log.clone()));
        }
        let status = match outcome {
            Ok(()) if plan.delivery().forbids_manifest_edit(manifest, ctx) => unmountable_status(),
            Ok(()) => PatchStatus::Applied,
            Err(reason) => PatchStatus::Failed { reason },
        };
        run.finish(idx, status, logs, &mut observer);
    }

    for &idx in plan.finalizers() {
        let Some(patch) = code[idx].filter(|patch| patch.finalizes()) else {
            continue;
        };
        if !run.applied(idx) {
            continue;
        }
        let _span = info_span!("after_dependents", patch = patch.reference()).entered();
        ctx.begin_patch(patch.reference(), plan.options(idx).clone());
        let manifest = ctx.apk().manifest_revision();
        let outcome = guarded(|| patch.invoke(PatchPhase::Finalize, ctx));
        let logs = ctx.take_log_entries();
        for log in &logs {
            observer(ProgressEvent::Log(log.clone()));
        }
        run.append_logs(idx, logs);
        match outcome {
            Ok(()) if plan.delivery().forbids_manifest_edit(manifest, ctx) => {
                run.set_status(idx, unmountable_status());
            }
            Ok(()) => {}
            Err(reason) => run.set_status(
                idx,
                PatchStatus::Failed {
                    reason: format!("after_dependents: {reason}"),
                },
            ),
        }
        run.terminal(idx, &mut observer);
    }

    ctx.bind_app_entry()?;
    ctx.finish_hermes()?;
    info!("patch application finished");
    Ok(run.into_results())
}

/// The outcome of `plan` without touching the APK or loading patch code: which
/// patches would run and which would be skipped.
pub fn validate_patches(specs: &[&PatchSpec], plan: &ResolvedPlan) -> Vec<PatchResult> {
    let code = vec![None; specs.len()];
    let mut run = Run::new(specs, plan, &code);
    for &idx in plan.order() {
        let status = run.blocked(idx).unwrap_or(PatchStatus::Applied);
        run.finish(idx, status, Vec::new(), &mut |_| {});
    }
    run.into_results()
}

/// The code of each patch in `specs`, by position, when `patches` holds it.
fn loaded_code<'a>(
    specs: &[&PatchSpec],
    plan: &ResolvedPlan,
    patches: &[&'a Patch],
) -> Result<Vec<Option<&'a Patch>>> {
    let by_reference: HashMap<&str, &'a Patch> = patches
        .iter()
        .map(|&patch| (patch.reference(), patch))
        .collect();
    specs
        .iter()
        .enumerate()
        .map(|(idx, spec)| {
            let reference = spec.reference();
            match by_reference.get(reference.as_str()) {
                Some(&patch) => Ok(Some(patch)),
                None if plan.executes(idx) => Err(PatcherError::NotLoaded(reference)),
                None => Ok(None),
            }
        })
        .collect()
}

struct Run<'a> {
    specs: &'a [&'a PatchSpec],
    plan: &'a ResolvedPlan,
    code: &'a [Option<&'a Patch>],
    results: Vec<Option<PatchResult>>,
}

impl<'a> Run<'a> {
    fn new(
        specs: &'a [&'a PatchSpec],
        plan: &'a ResolvedPlan,
        code: &'a [Option<&'a Patch>],
    ) -> Self {
        Self {
            specs,
            plan,
            code,
            results: (0..specs.len()).map(|_| None).collect(),
        }
    }

    /// Why the patch cannot run, as its final status, or `None` when it can.
    fn blocked(&self, idx: usize) -> Option<PatchStatus> {
        if let Some(status) = self.plan.precluded(idx) {
            return Some(status.clone());
        }
        let skipped = |reason: String| Some(PatchStatus::Skipped { reason });
        for &dependency in self.plan.dependencies(idx) {
            let name = self.specs[dependency].reference();
            match self.results[dependency]
                .as_ref()
                .map(|result| &result.status)
            {
                Some(PatchStatus::Applied) => {}
                Some(PatchStatus::Skipped { reason }) => {
                    return skipped(format!("dependency '{name}' skipped: {reason}"));
                }
                Some(PatchStatus::Unmountable { .. }) => {
                    return skipped(format!("dependency '{name}' {CHANGES_MANIFEST}"));
                }
                Some(PatchStatus::Failed { reason }) => {
                    return skipped(format!("dependency '{name}' failed: {reason}"));
                }
                None => return skipped(format!("dependency '{name}' was not executed")),
            }
        }
        None
    }

    fn applied(&self, idx: usize) -> bool {
        matches!(
            self.results[idx].as_ref().map(|r| &r.status),
            Some(PatchStatus::Applied)
        )
    }

    fn finish(
        &mut self,
        idx: usize,
        status: PatchStatus,
        logs: Vec<LogEntry>,
        observer: &mut impl FnMut(ProgressEvent),
    ) {
        let spec = self.specs[idx];
        self.results[idx] = Some(PatchResult {
            patch: spec.reference(),
            hidden: spec.hidden,
            required_by: self
                .plan
                .required_by(idx)
                .map(|dependent| self.specs[dependent].reference())
                .collect(),
            status,
            logs,
        });
        if !self.applied(idx) || !self.code[idx].is_some_and(Patch::finalizes) {
            self.terminal(idx, observer);
        }
    }

    fn result_mut(&mut self, idx: usize) -> &mut PatchResult {
        self.results[idx]
            .as_mut()
            .expect("execution creates a result before finalization")
    }

    fn append_logs(&mut self, idx: usize, logs: Vec<LogEntry>) {
        self.result_mut(idx).logs.extend(logs);
    }

    fn set_status(&mut self, idx: usize, status: PatchStatus) {
        self.result_mut(idx).status = status;
    }

    fn terminal(&mut self, idx: usize, observer: &mut impl FnMut(ProgressEvent)) {
        let result = self.result_mut(idx);
        observer(ProgressEvent::Finished {
            patch: result.patch.clone(),
            status: result.status.clone(),
        });
    }

    fn into_results(mut self) -> Vec<PatchResult> {
        self.plan
            .order()
            .iter()
            .map(|&idx| {
                self.results[idx]
                    .take()
                    .expect("plan visits every patch exactly once after execution")
            })
            .collect()
    }
}

fn guarded(hook: impl FnOnce() -> Result<()>) -> std::result::Result<(), String> {
    match panic::catch_unwind(AssertUnwindSafe(hook)) {
        Ok(Ok(())) => Ok(()),
        Ok(Err(error)) => Err(error.to_string()),
        Err(panic) => Err(format!(
            "panic: {}",
            panic
                .downcast_ref::<String>()
                .cloned()
                .or_else(|| panic.downcast_ref::<&str>().map(ToString::to_string))
                .unwrap_or_else(|| "unknown panic".to_owned())
        )),
    }
}

#[cfg(test)]
#[path = "run_tests.rs"]
mod tests;
