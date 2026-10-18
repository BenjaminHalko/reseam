// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::cell::RefCell;

use reseam_journal::{Journal, Savepoint};
use rustc_hash::FxHashMap;

use super::types::PoolOrigin;

thread_local! {
    static POOL_COPIES: RefCell<PoolCopies> = RefCell::new(PoolCopies::default());
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum PoolKind {
    Handle,
    CallSite,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct PoolCopy {
    origin: PoolOrigin,
    destination: u32,
    kind: PoolKind,
}

/// Where call sites and method handles were already copied, so each is copied once.
#[derive(Default)]
struct PoolCopies {
    copies: FxHashMap<PoolCopy, u32>,
    journal: Journal<PoolCopy, (PoolCopy, Option<u32>)>,
}

pub(crate) fn copied_pool(origin: PoolOrigin, destination: u32, kind: PoolKind) -> Option<u32> {
    POOL_COPIES.with(|copies| {
        copies
            .borrow()
            .copies
            .get(&PoolCopy {
                origin,
                destination,
                kind,
            })
            .copied()
    })
}

pub(crate) fn remember_pool(origin: PoolOrigin, destination: u32, kind: PoolKind, index: u32) {
    let copy = PoolCopy {
        origin,
        destination,
        kind,
    };
    POOL_COPIES.with(|copies| {
        let copies = &mut *copies.borrow_mut();
        let previous = copies.copies.insert(copy, index);
        copies.journal.record(copy, || (copy, previous));
    });
}

pub(super) fn open_savepoint(savepoint: Savepoint) {
    POOL_COPIES.with(|copies| copies.borrow_mut().journal.open(savepoint));
}

pub(super) fn release_savepoint(savepoint: Savepoint) {
    POOL_COPIES.with(|copies| copies.borrow_mut().journal.release(savepoint));
}

pub(super) fn rollback_to(savepoint: Savepoint) {
    POOL_COPIES.with(|copies| {
        let copies = &mut *copies.borrow_mut();
        for (copy, previous) in copies.journal.rollback(savepoint) {
            match previous {
                Some(index) => copies.copies.insert(copy, index),
                None => copies.copies.remove(&copy),
            };
        }
    });
}

pub(super) fn reset() {
    POOL_COPIES.with(|copies| *copies.borrow_mut() = PoolCopies::default());
}
