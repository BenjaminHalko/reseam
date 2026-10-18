// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::path::PathBuf;
#[cfg(feature = "kotlin")]
use std::sync::Arc;

pub(super) use super::handle_table::HandleSpace;
#[cfg(feature = "kotlin")]
use jni::objects::{Global, JObject};

mod access;
mod locations;

pub(super) use access::{
    log_mutation, method_ref, with_class_mut, with_code, with_instruction, with_method_mut,
};
pub(super) use locations::{
    alloc_class, alloc_method, alloc_methods, class_location, forget_class, forget_method,
    method_location, relocate_method, with_handles,
};

use super::pool_copies;
pub(super) use super::pool_copies::{PoolKind, copied_pool, remember_pool};
use super::xml;

use crate::error::{PatcherError, Result as PatcherResult};

use crate::context::PatchContext;
pub(super) use crate::context::{code_mut, method_mut};

thread_local! {
    #[cfg(feature = "kotlin")]
    static KOTLIN_RUN: RefCell<Option<Arc<Global<JObject<'static>>>>> = const { RefCell::new(None) };
    static RUN_ACTIVE: Cell<bool> = const { Cell::new(false) };
    static CTX_PTR: Cell<*mut ()> = const { Cell::new(std::ptr::null_mut()) };
    static FAILURE: RefCell<Option<String>> = const { RefCell::new(None) };
    static REVISION: Cell<u64> = const { Cell::new(0) };
    static CHANGES: RefCell<VecDeque<MethodChange>> = const { RefCell::new(VecDeque::new()) };
    static STRUCTURAL_REVISION: Cell<u64> = const { Cell::new(0) };
    static BUNDLE: RefCell<Option<RunningBundle>> = const { RefCell::new(None) };
}

/// The bundle whose patch is running: who it is and where its files are.
pub(crate) struct RunningBundle {
    pub(crate) name: String,
    pub(crate) version: String,
    pub(crate) license: String,
    pub(crate) directory: PathBuf,
}

#[cfg(test)]
impl RunningBundle {
    pub(crate) fn at(directory: PathBuf) -> Self {
        Self {
            name: "test".into(),
            version: String::new(),
            license: String::new(),
            directory,
        }
    }
}

pub(super) struct ContextGuard<'ctx, 'apk> {
    _context: std::marker::PhantomData<&'ctx mut PatchContext<'apk>>,
}

pub(crate) struct RunGuard;

impl RunGuard {
    pub fn enter() -> PatcherResult<Self> {
        if RUN_ACTIVE.with(|active| active.replace(true)) {
            return Err(PatcherError::Bridge(
                "nested patch run on the same thread".into(),
            ));
        }
        reset();
        Ok(Self)
    }
}

impl Drop for RunGuard {
    fn drop(&mut self) {
        reset();
        RUN_ACTIVE.with(|active| active.set(false));
    }
}

/// The run's Kotlin patch runtime, once a patch has created it.
#[cfg(feature = "kotlin")]
pub(super) fn existing_kotlin_run() -> Option<Arc<Global<JObject<'static>>>> {
    KOTLIN_RUN.with(|run| run.borrow().clone())
}

#[cfg(feature = "kotlin")]
pub(super) fn kotlin_run(
    create: impl FnOnce() -> PatcherResult<Global<JObject<'static>>>,
) -> PatcherResult<Arc<Global<JObject<'static>>>> {
    KOTLIN_RUN.with(|run| {
        let mut run = run.borrow_mut();
        if let Some(run) = run.as_ref() {
            return Ok(run.clone());
        }
        let value = Arc::new(create()?);
        *run = Some(value.clone());
        Ok(value)
    })
}

fn reset() {
    super::hermes::reset();
    #[cfg(feature = "kotlin")]
    KOTLIN_RUN.with(|run| *run.borrow_mut() = None);
    pool_copies::reset();
    locations::reset();
    FAILURE.with(|failure| *failure.borrow_mut() = None);
    REVISION.with(|revision| revision.set(0));
    STRUCTURAL_REVISION.with(|revision| revision.set(0));
    CHANGES.with(|changes| changes.borrow_mut().clear());
    xml::reset();
}

pub(super) fn record_failure(error: impl std::fmt::Display) {
    FAILURE.with(|failure| {
        failure
            .borrow_mut()
            .get_or_insert_with(|| error.to_string());
    });
}

pub(super) fn checked<T: Default>(result: Result<T, impl std::fmt::Display>) -> T {
    match result {
        Ok(value) => value,
        Err(error) => {
            record_failure(error);
            T::default()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn reseam_patch_call_failed() -> i32 {
    i32::from(check_call().is_err())
}

#[boltffi::export]
pub fn check_call() -> Result<(), String> {
    if !context_is_active() {
        return Err("patch callbacks require an active context on this thread".into());
    }
    FAILURE.with(|failure| {
        failure
            .borrow()
            .as_ref()
            .map_or(Ok(()), |reason| Err(reason.clone()))
    })
}

#[boltffi::export]
pub fn check_invocation() -> Result<(), String> {
    FAILURE
        .with(|failure| {
            failure
                .borrow()
                .as_ref()
                .map_or(Ok(()), |reason| Err(reason.clone()))
        })
        .and_then(|()| xml::finish())
}

pub(super) fn changed() {
    REVISION.with(|revision| revision.set(revision.get().wrapping_add(1)));
    STRUCTURAL_REVISION.with(|revision| revision.set(mutation_revision()));
    CHANGES.with(|changes| changes.borrow_mut().clear());
}

struct MethodChange {
    revision: u64,
    handle: u32,
}

pub(super) fn method_changed(handle: u32) {
    REVISION.with(|revision| revision.set(revision.get().wrapping_add(1)));
    CHANGES.with(|changes| {
        let mut changes = changes.borrow_mut();
        if changes.len() == 1024 {
            let removed = changes.pop_front().expect("change journal is full");
            STRUCTURAL_REVISION.with(|revision| revision.set(removed.revision));
        }
        changes.push_back(MethodChange {
            revision: mutation_revision(),
            handle,
        });
    });
}

/// Returns changes since a reader's revision. Readers older than the bounded
/// journal receive `None` and must discard all cached data, just as for a
/// structural mutation. `Some` lists only the methods that changed.
#[boltffi::export]
pub fn mutation_changes(since: u64) -> Option<Vec<u32>> {
    if since > mutation_revision() || STRUCTURAL_REVISION.with(|revision| since < revision.get()) {
        return None;
    }
    let mut handles: Vec<_> = CHANGES.with(|changes| {
        changes
            .borrow()
            .iter()
            .rev()
            .take_while(|change| change.revision > since)
            .map(|change| change.handle)
            .collect()
    });
    handles.sort_unstable();
    handles.dedup();
    Some(handles)
}

#[boltffi::export]
pub fn mutation_revision() -> u64 {
    REVISION.with(Cell::get)
}

impl<'ctx, 'apk> ContextGuard<'ctx, 'apk> {
    pub fn enter(ctx: &'ctx mut PatchContext<'apk>, bundle: RunningBundle) -> PatcherResult<Self> {
        CTX_PTR.with(|cell| {
            if !cell.get().is_null() {
                return Err(PatcherError::Bridge("nested patch context".into()));
            }
            cell.set(std::ptr::from_mut(ctx).cast());
            Ok(())
        })?;
        BUNDLE.with(|running| *running.borrow_mut() = Some(bundle));
        FAILURE.with(|failure| *failure.borrow_mut() = None);
        Ok(Self {
            _context: std::marker::PhantomData,
        })
    }

    pub fn finish(self) -> PatcherResult<()> {
        if let Err(error) = xml::finish() {
            record_failure(error);
        }
        let outcome = FAILURE.with(|failure| {
            failure
                .borrow_mut()
                .take()
                .map_or(Ok(()), |reason| Err(PatcherError::Bridge(reason)))
        });
        drop(self);
        outcome
    }
}

impl Drop for ContextGuard<'_, '_> {
    fn drop(&mut self) {
        CTX_PTR.with(|cell| cell.set(std::ptr::null_mut()));
        BUNDLE.with(|running| *running.borrow_mut() = None);
        xml::reset();
    }
}

pub(super) fn context_is_active() -> bool {
    CTX_PTR.with(|cell| !cell.get().is_null())
}

pub(super) fn with_ctx<R: Default>(f: impl FnOnce(&mut PatchContext<'_>) -> R) -> R {
    checked(try_with_ctx(f))
}

pub(super) fn with_ctx_result<R>(
    f: impl FnOnce(&mut PatchContext<'_>) -> Result<R, String>,
) -> Result<R, String> {
    try_with_ctx(f).map_err(|error| error.to_string())?
}

pub(super) fn try_with_ctx<R>(f: impl FnOnce(&mut PatchContext<'_>) -> R) -> PatcherResult<R> {
    CTX_PTR.with(|cell| {
        let ptr = cell.get();
        if ptr.is_null() {
            return Err(PatcherError::Bridge(
                "patch context is not active on this thread".into(),
            ));
        }
        // SAFETY: the pointer was set from a live `&mut PatchContext` by
        // `ContextGuard::enter` on this thread and is cleared before that
        // borrow ends; exported functions run on the same thread, one at a time.
        Ok(f(unsafe { &mut *ptr.cast::<PatchContext<'_>>() }))
    })
}

pub(super) fn bundle_path(relative: &str) -> PathBuf {
    BUNDLE.with(|running| match running.borrow().as_ref() {
        Some(bundle) => bundle.directory.join(relative),
        None => PathBuf::from(relative),
    })
}

pub(super) fn with_running_bundle<R>(f: impl FnOnce(&RunningBundle) -> R) -> Result<R, String> {
    BUNDLE.with(|running| {
        running
            .borrow()
            .as_ref()
            .map(f)
            .ok_or_else(|| "no patch is running on this thread".to_owned())
    })
}
