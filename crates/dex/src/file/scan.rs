// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

mod class;
mod inspection;
mod view;

pub use inspection::{MethodSummary, summarize_resident};
pub use view::{InstructionHit, InstructionSite, MethodHit, MethodView};

use std::ops::ControlFlow;

use rayon::prelude::*;
use rustc_hash::FxHashSet;
use smallvec::SmallVec;

use super::ref_index::Slot;
use super::{DexFile, RefQuery};
use crate::error::Result;
pub use crate::read::class::MemberCounts;
use crate::types::MethodIdx;

/// One class a scan visits: the listed method positions, or all of them.
struct ClassVisit {
    class_idx: usize,
    methods: Option<SmallVec<[Slot; 4]>>,
}

impl DexFile {
    /// Scans methods across all classes, returning the first `Some`. Sequential
    /// with early exit.
    pub fn scan_methods_find<T>(
        &self,
        query: &RefQuery,
        mut f: impl FnMut(&MethodView<'_>) -> Result<Option<T>>,
    ) -> Result<Option<T>> {
        for visit in self.visits(query)? {
            let flow = self.scan_class(visit.class_idx, visit.methods.as_deref(), &mut |view| {
                Ok(match f(view)? {
                    Some(value) => ControlFlow::Break(value),
                    None => ControlFlow::Continue(()),
                })
            })?;
            if let ControlFlow::Break(value) = flow {
                return Ok(Some(value));
            }
        }
        Ok(None)
    }

    /// Scans every method across all classes in parallel, collecting each `Some`
    /// in class-then-method order. Only methods the reference index matches
    /// for `query` are visited.
    pub fn scan_methods_collect<T: Send>(
        &self,
        query: &RefQuery,
        f: impl Fn(&MethodView<'_>) -> Result<Option<T>> + Sync,
    ) -> Result<Vec<T>> {
        let per_class: Vec<Vec<T>> = self
            .visits(query)?
            .into_par_iter()
            .map(|visit| {
                let mut hits = Vec::new();
                let _: ControlFlow<()> =
                    self.scan_class(visit.class_idx, visit.methods.as_deref(), &mut |view| {
                        if let Some(value) = f(view)? {
                            hits.push(value);
                        }
                        Ok(ControlFlow::Continue(()))
                    })?;
                Ok(hits)
            })
            .collect::<Result<_>>()?;
        self.release_pages();
        Ok(per_class.into_iter().flatten().collect())
    }

    /// Visits the definitions of `methods`, ids of this DEX, in class-then-method
    /// order, collecting each `Some`. Only the classes defining them are read.
    pub fn scan_method_definitions<T: Send>(
        &self,
        methods: &[MethodIdx],
        f: impl Fn(&MethodView<'_>) -> Result<Option<T>> + Sync,
    ) -> Result<Vec<T>> {
        let wanted: FxHashSet<MethodIdx> = methods.iter().copied().collect();
        let mut classes: Vec<usize> = methods
            .iter()
            .filter_map(|&method| self.classes.index_of_type(self.method_id(method).class))
            .collect();
        classes.sort_unstable();
        classes.dedup();
        let per_class: Vec<Vec<T>> = classes
            .into_par_iter()
            .map(|class_idx| {
                let mut hits = Vec::new();
                let _: ControlFlow<()> = self.scan_class(class_idx, None, &mut |view| {
                    if wanted.contains(&view.method)
                        && let Some(value) = f(view)?
                    {
                        hits.push(value);
                    }
                    Ok(ControlFlow::Continue(()))
                })?;
                Ok(hits)
            })
            .collect::<Result<_>>()?;
        Ok(per_class.into_iter().flatten().collect())
    }

    /// Visits every instruction of every method the index matches for
    /// `query`, collecting each `Some`.
    pub fn scan_instructions<T: Send>(
        &self,
        query: &RefQuery,
        f: impl Fn(&InstructionSite<'_>) -> Option<T> + Sync,
    ) -> Result<Vec<T>> {
        let per_method: Vec<Vec<T>> = self.scan_methods_collect(query, |view| {
            let mut hits = Vec::new();
            let mut insn_idx = 0;
            view.for_each_instruction(|instruction| {
                if let Some(value) = f(&InstructionSite {
                    class_idx: view.class_idx,
                    method_pos: view.method_pos,
                    kind: view.kind,
                    insn_idx,
                    instruction,
                }) {
                    hits.push(value);
                }
                insn_idx += 1;
                true
            })?;
            Ok((!hits.is_empty()).then_some(hits))
        })?;
        Ok(per_method.into_iter().flatten().collect())
    }

    /// The classes a scan for `query` visits, in class order: every class for an
    /// empty query, otherwise the index's matches, and for resident classes,
    /// which the index does not cover, the methods their own references match.
    fn visits(&self, query: &RefQuery) -> Result<Vec<ClassVisit>> {
        let all = |class_idx| ClassVisit {
            class_idx,
            methods: None,
        };
        if query.is_empty() {
            return Ok((0..self.classes.len()).map(all).collect());
        }
        let indexed = self
            .ref_index()?
            .matches(query)
            .into_iter()
            .filter_map(|m| {
                (!self.classes.is_resident(m.class_idx)).then_some(ClassVisit {
                    class_idx: m.class_idx,
                    methods: Some(m.positions),
                })
            });
        let resident = self.classes.resident_indices().iter().filter_map(|&i| {
            let class_idx = i as usize;
            let positions = self.classes.resident_refs(class_idx)?.matching(query);
            (!positions.is_empty()).then_some(ClassVisit {
                class_idx,
                methods: Some(positions),
            })
        });
        let mut visits: Vec<ClassVisit> = indexed.chain(resident).collect();
        visits.sort_unstable_by_key(|visit| visit.class_idx);
        Ok(visits)
    }
}
