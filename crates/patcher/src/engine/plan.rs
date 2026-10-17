// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::{HashMap, HashSet, VecDeque};

use super::{PatchIndex, PatchStatus};

use crate::context::PatchContext;
use crate::error::{PatcherError, Result};
use crate::options::PatchOptions;
use crate::patch::PatchSpec;

pub use reseam_model::PatchSelection;

/// How a run's output reaches the device, which decides what a patch may change.
#[derive(Debug, Clone)]
pub enum Delivery {
    /// The output installs as an app of its own.
    Install,
    /// The output replaces the installed app's files, and the system keeps the
    /// manifest it read from the installed app. A patch that edits the manifest
    /// finishes unmountable, and the patches that need it are skipped. The run
    /// still holds that patch's edits, so the caller builds again from the
    /// original APK with it listed in `unmountable`, which keeps it from running.
    Mount { unmountable: Vec<String> },
}

impl Delivery {
    fn unmountable(&self) -> &[String] {
        match self {
            Self::Install => &[],
            Self::Mount { unmountable } => unmountable,
        }
    }

    /// Whether the manifest changed since `revision` in a build that cannot apply it.
    pub(super) fn forbids_manifest_edit(&self, revision: u64, ctx: &PatchContext<'_>) -> bool {
        matches!(self, Self::Mount { .. }) && ctx.apk().manifest_revision() != revision
    }
}

pub(super) const CHANGES_MANIFEST: &str = "changes the manifest, which a mount does not apply";

pub(super) fn unmountable_status() -> PatchStatus {
    PatchStatus::Unmountable {
        reason: CHANGES_MANIFEST.to_owned(),
    }
}

/// What a run of `specs` covers, decided from the patch declarations alone, so
/// a host can load only the code of the patches it [executes](Self::executes).
/// Indices are positions in the `specs` the plan was resolved from.
#[derive(Debug)]
pub struct ResolvedPlan {
    order: Vec<usize>,
    finalizers: Vec<usize>,
    dependencies: Vec<Vec<usize>>,
    dependents: Vec<Vec<usize>>,
    selected: Vec<bool>,
    desired: Vec<bool>,
    precluded: Vec<Option<PatchStatus>>,
    executable: Vec<bool>,
    options: Vec<PatchOptions>,
    delivery: Delivery,
}

impl ResolvedPlan {
    /// Patches a mount delivery lists as unmountable stay selected so the run
    /// can report them, but neither they, the patches that need them, nor
    /// dependencies only those need will run.
    pub fn resolve(
        specs: &[&PatchSpec],
        selection: &PatchSelection,
        delivery: Delivery,
        package: Option<&str>,
        version: Option<&str>,
    ) -> Result<Self> {
        let index = PatchIndex::new(specs)?;
        let DependencyGraph {
            dependencies,
            dependents,
            missing,
        } = dependency_edges(specs, &index);
        let order = topological_order(specs, &dependencies, &dependents)?;
        let finalizers = topological_order(specs, &dependents, &dependencies)?;

        let lookup = |patch: &String| index.resolve(patch, package);
        let enabled: HashSet<usize> = selection.enable.iter().map(lookup).collect::<Result<_>>()?;
        for &idx in &enabled {
            if let Some(missing) = &missing[idx] {
                return Err(missing.error(specs[idx].reference()));
            }
        }
        let unavailable: Vec<Option<String>> = missing
            .iter()
            .map(|missing| missing.as_ref().map(MissingDependency::skip_reason))
            .collect();
        let roots: Vec<usize> = (0..specs.len())
            .filter(|&i| enabled.contains(&i) || specs[i].in_preset(selection.preset, package))
            .collect();
        let mut selected = vec![false; specs.len()];
        for &idx in &roots {
            selected[idx] = true;
        }

        let mut disabled = vec![false; specs.len()];
        for patch in &selection.disable {
            let idx = lookup(patch)?;
            if enabled.contains(&idx) {
                return Err(PatcherError::InvalidSelection(format!(
                    "patch '{patch}' cannot be both selected and disabled"
                )));
            }
            disabled[idx] = true;
        }

        let mut excluded = vec![false; specs.len()];
        for patch in delivery.unmountable() {
            excluded[lookup(patch)?] = true;
        }
        let mut needs_excluded = excluded.clone();
        for &idx in &order {
            needs_excluded[idx] |= dependencies[idx]
                .iter()
                .any(|&dependency| needs_excluded[dependency]);
        }

        let precluded: Vec<Option<PatchStatus>> = specs
            .iter()
            .enumerate()
            .map(|(idx, spec)| {
                if excluded[idx] {
                    return Some(unmountable_status());
                }
                let reason = if disabled[idx] {
                    Some("disabled explicitly".to_owned())
                } else if let Some(reason) = &unavailable[idx] {
                    Some(reason.clone())
                } else if selection.ignore_versions {
                    spec.package_incompatibility(package)
                } else {
                    spec.incompatibility(package, version)
                };
                reason.map(|reason| PatchStatus::Skipped { reason })
            })
            .collect();

        let desired = desired_patches(&roots, &selected, &dependencies, |idx| {
            precluded[idx].is_none() && !needs_excluded[idx]
        });

        let options = resolve_options(specs, selection, &index, package, &desired, &disabled)?;
        let order: Vec<usize> = order.into_iter().filter(|&idx| desired[idx]).collect();
        let finalizers = finalizers.into_iter().filter(|&idx| desired[idx]).collect();
        let mut executable = vec![false; specs.len()];
        for &idx in &order {
            executable[idx] = precluded[idx].is_none()
                && dependencies[idx]
                    .iter()
                    .all(|&dependency| executable[dependency]);
        }

        Ok(Self {
            order,
            finalizers,
            dependencies,
            dependents,
            selected,
            desired,
            precluded,
            executable,
            options,
            delivery,
        })
    }

    /// Whether the run may execute the patch at `idx`: it is covered, and
    /// neither it nor a dependency is ruled out before the run starts. A patch
    /// that executes can still be skipped when a dependency fails during the run.
    pub fn executes(&self, idx: usize) -> bool {
        self.executable[idx]
    }

    /// The patches this run covers, in dependency order: the selection and the dependencies it needs.
    pub(crate) fn order(&self) -> &[usize] {
        &self.order
    }

    pub(crate) fn dependencies(&self, idx: usize) -> &[usize] {
        &self.dependencies[idx]
    }

    pub(crate) fn finalizers(&self) -> &[usize] {
        &self.finalizers
    }

    pub(crate) fn required_by(&self, idx: usize) -> impl Iterator<Item = usize> + '_ {
        let dependents = if self.selected[idx] {
            &[][..]
        } else {
            &self.dependents[idx]
        };
        dependents
            .iter()
            .copied()
            .filter(|&dependent| self.desired[dependent])
    }

    /// Why the patch cannot run regardless of how its dependencies fare.
    pub(crate) fn precluded(&self, idx: usize) -> Option<&PatchStatus> {
        self.precluded[idx].as_ref()
    }

    pub(crate) fn options(&self, idx: usize) -> &PatchOptions {
        &self.options[idx]
    }

    pub(crate) fn delivery(&self) -> &Delivery {
        &self.delivery
    }
}

/// The selection roots and the dependencies they need. A dependency is desired
/// only while an applicable, enabled patch needs it. Selection roots remain
/// desired so validation can report why they cannot run, but incompatibility or
/// explicit disabling stops their dependency chain from propagating further.
fn desired_patches(
    roots: &[usize],
    selected: &[bool],
    dependencies: &[Vec<usize>],
    can_propagate: impl Fn(usize) -> bool,
) -> Vec<bool> {
    let mut desired = selected.to_vec();
    let mut expanded = vec![false; selected.len()];
    let mut stack: Vec<usize> = roots
        .iter()
        .copied()
        .filter(|&idx| can_propagate(idx))
        .collect();
    while let Some(idx) = stack.pop() {
        if std::mem::replace(&mut expanded[idx], true) {
            continue;
        }
        for &dependency in &dependencies[idx] {
            desired[dependency] = true;
            if can_propagate(dependency) {
                stack.push(dependency);
            }
        }
    }
    desired
}

fn resolve_options(
    specs: &[&PatchSpec],
    selection: &PatchSelection,
    index: &PatchIndex<'_>,
    package: Option<&str>,
    desired: &[bool],
    disabled: &[bool],
) -> Result<Vec<PatchOptions>> {
    let mut configured = HashMap::new();
    for (patch, options) in &selection.options {
        let idx = index.resolve(patch, package)?;
        if configured
            .insert(idx, PatchOptions::from(options.clone()))
            .is_some()
        {
            return Err(PatcherError::InvalidSelection(format!(
                "options for patch '{}' were supplied under multiple selectors",
                specs[idx].reference()
            )));
        }
        if !desired[idx] || disabled[idx] {
            return Err(PatcherError::InvalidSelection(format!(
                "patch '{patch}' has options configured but is not enabled by the selection"
            )));
        }
    }
    specs
        .iter()
        .enumerate()
        .map(|(idx, spec)| {
            if !desired[idx] || disabled[idx] {
                return Ok(PatchOptions::default());
            }
            PatchOptions::resolve(&spec.reference(), &spec.options, configured.get(&idx))
        })
        .collect()
}

struct MissingDependency {
    dependency: String,
    bundle: String,
    availability: BundleAvailability,
}

enum BundleAvailability {
    Loaded,
    Missing,
}

impl MissingDependency {
    fn error(&self, patch: String) -> PatcherError {
        let (dependency, bundle) = (self.dependency.clone(), self.bundle.clone());
        if matches!(self.availability, BundleAvailability::Loaded) {
            PatcherError::MissingDependency {
                patch,
                dependency,
                bundle,
            }
        } else {
            PatcherError::MissingBundle {
                patch,
                dependency,
                bundle,
            }
        }
    }

    fn skip_reason(&self) -> String {
        if matches!(self.availability, BundleAvailability::Loaded) {
            format!(
                "depends on {}, which bundle '{}' does not declare",
                self.dependency, self.bundle
            )
        } else {
            format!(
                "depends on {}; load bundle '{}' alongside",
                self.dependency, self.bundle
            )
        }
    }
}

struct DependencyGraph {
    dependencies: Vec<Vec<usize>>,
    dependents: Vec<Vec<usize>>,
    missing: Vec<Option<MissingDependency>>,
}

fn dependency_edges(specs: &[&PatchSpec], index: &PatchIndex<'_>) -> DependencyGraph {
    let mut dependencies = vec![Vec::new(); specs.len()];
    let mut dependents = vec![Vec::new(); specs.len()];
    let mut missing: Vec<Option<MissingDependency>> = (0..specs.len()).map(|_| None).collect();
    for (idx, spec) in specs.iter().enumerate() {
        for dependency in &spec.dependencies {
            let Some(dependency_idx) = index.reference(dependency) else {
                let bundle = dependency.split_once('/').map_or("", |(bundle, _)| bundle);
                missing[idx].get_or_insert_with(|| MissingDependency {
                    dependency: dependency.clone(),
                    bundle: bundle.to_owned(),
                    availability: if specs.iter().any(|spec| spec.bundle == bundle) {
                        BundleAvailability::Loaded
                    } else {
                        BundleAvailability::Missing
                    },
                });
                continue;
            };
            dependencies[idx].push(dependency_idx);
            dependents[dependency_idx].push(idx);
        }
    }
    DependencyGraph {
        dependencies,
        dependents,
        missing,
    }
}

fn topological_order(
    specs: &[&PatchSpec],
    dependencies: &[Vec<usize>],
    dependents: &[Vec<usize>],
) -> Result<Vec<usize>> {
    let mut in_degree: Vec<usize> = dependencies.iter().map(Vec::len).collect();
    let mut queue: VecDeque<usize> = (0..specs.len()).filter(|&i| in_degree[i] == 0).collect();
    let mut order = Vec::with_capacity(specs.len());
    while let Some(idx) = queue.pop_front() {
        order.push(idx);
        for &dependent in &dependents[idx] {
            in_degree[dependent] -= 1;
            if in_degree[dependent] == 0 {
                queue.push_back(dependent);
            }
        }
    }
    if order.len() != specs.len() {
        let names = (0..specs.len())
            .filter(|&i| in_degree[i] > 0)
            .map(|i| specs[i].reference())
            .collect();
        return Err(PatcherError::DependencyCycle(names));
    }
    Ok(order)
}
