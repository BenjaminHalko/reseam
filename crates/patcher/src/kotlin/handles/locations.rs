// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::cell::RefCell;

use super::{changed, record_failure};
use crate::context::{ClassLocation, MethodLocation};
use crate::kotlin::handle_table::HandleTable;

thread_local! {
    static HANDLES: RefCell<HandleTable> = RefCell::new(HandleTable::default());
}

pub(crate) fn with_handles<R>(f: impl FnOnce(&mut HandleTable) -> R) -> R {
    HANDLES.with(|handles| f(&mut handles.borrow_mut()))
}

pub(crate) fn alloc_method(location: MethodLocation) -> u32 {
    HANDLES.with(|h| h.borrow_mut().alloc_method(location))
}

pub(crate) fn alloc_methods(locations: impl IntoIterator<Item = MethodLocation>) -> Vec<u32> {
    HANDLES.with(|h| {
        let mut h = h.borrow_mut();
        locations.into_iter().map(|l| h.alloc_method(l)).collect()
    })
}

pub(crate) fn method_location(handle: u32) -> Option<MethodLocation> {
    let location = HANDLES.with(|h| h.borrow().get_method(handle));
    if location.is_none() {
        record_failure(format!("invalid or removed method handle {handle}"));
    }
    location
}

pub(crate) fn forget_method(location: MethodLocation) {
    HANDLES.with(|h| h.borrow_mut().forget_method(location));
    changed();
}

pub(crate) fn relocate_method(old: MethodLocation, new: MethodLocation) {
    HANDLES.with(|handles| handles.borrow_mut().relocate_method(old, new));
    changed();
}

pub(crate) fn forget_class(location: ClassLocation) {
    HANDLES.with(|handles| handles.borrow_mut().forget_class(location));
    changed();
}

pub(crate) fn alloc_class(location: ClassLocation) -> u32 {
    HANDLES.with(|h| h.borrow_mut().alloc_class(location))
}

pub(crate) fn class_location(handle: u32) -> Option<ClassLocation> {
    let location = HANDLES.with(|h| h.borrow().get_class(handle));
    if location.is_none() {
        record_failure(format!("invalid or removed class handle {handle}"));
    }
    location
}

pub(super) fn reset() {
    HANDLES.with(|handles| *handles.borrow_mut() = HandleTable::default());
}
