// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use reseam_journal::{Journal, Savepoint};
use rustc_hash::FxHashMap;

use super::{HandleSpace, INVALID_HANDLE};
use crate::kotlin::handles::record_failure;

#[derive(Default)]
pub(super) struct Handles {
    pub(super) identities: HandleSpace,
    pub(super) keys: Vec<u64>,
    pub(super) sorted_len: usize,
    pub(super) index: FxHashMap<u64, u32>,
    pub(super) relocated: FxHashMap<u32, u64>,
    journal: Journal<Part, Undo>,
}

#[derive(Clone, PartialEq, Eq, Hash)]
enum Part {
    Len,
    SortedLen,
    Key(usize),
    Relocated(u32),
}

enum Undo {
    /// Slots from this length on were allocated since; they end removed, so
    /// their handles are never given to another location.
    Len(usize),
    SortedLen(usize),
    Key(usize, u64),
    Relocated(u32, Option<u64>),
}

impl Handles {
    pub(super) fn alloc(&mut self, key: u64, removed_bit: u64) -> u32 {
        if let Some(&h) = self.index.get(&key) {
            return h;
        }
        let sorted = &self.keys[..self.sorted_len];
        let first = sorted.partition_point(|held| held & !removed_bit < key);
        if let Some(offset) = sorted[first..]
            .iter()
            .take_while(|held| **held & !removed_bit == key)
            .position(|held| held & removed_bit == 0)
        {
            return self.identities.handle(first + offset);
        }
        let h = match self.identities.allocate(self.keys.len()) {
            Ok(handle) => handle,
            Err(error) => {
                record_failure(error);
                return INVALID_HANDLE;
            }
        };
        let len = self.keys.len();
        self.journal.record(Part::Len, || Undo::Len(len));
        if self.sorted_len == self.keys.len()
            && self
                .keys
                .last()
                .is_none_or(|&last| last & !removed_bit < key)
        {
            self.sorted_len += 1;
        } else {
            self.index.insert(key, h);
        }
        self.keys.push(key);
        h
    }

    pub(super) fn set_key(&mut self, slot: usize, key: u64) {
        let previous = std::mem::replace(&mut self.keys[slot], key);
        self.journal
            .record(Part::Key(slot), || Undo::Key(slot, previous));
    }

    pub(super) fn set_relocated(&mut self, handle: u32, key: u64) {
        let previous = self.relocated.insert(handle, key);
        self.journal.record(Part::Relocated(handle), || {
            Undo::Relocated(handle, previous)
        });
    }

    pub(super) fn set_sorted_len(&mut self, sorted_len: usize) {
        let previous = std::mem::replace(&mut self.sorted_len, sorted_len);
        self.journal
            .record(Part::SortedLen, || Undo::SortedLen(previous));
    }

    #[cfg(feature = "kotlin")]
    pub(super) fn savepoints(&self) -> impl Iterator<Item = Savepoint> + '_ {
        self.journal.savepoints()
    }

    pub(super) fn open_savepoint(&mut self, savepoint: Savepoint) {
        self.journal.open(savepoint);
    }

    pub(super) fn release_savepoint(&mut self, savepoint: Savepoint) {
        self.journal.release(savepoint);
    }

    pub(super) fn rollback_to(&mut self, savepoint: Savepoint, removed_bit: u64) {
        let undos = self.journal.rollback(savepoint);
        if undos.is_empty() {
            return;
        }
        for undo in undos {
            match undo {
                Undo::Len(len) => self.keys[len..]
                    .iter_mut()
                    .for_each(|key| *key |= removed_bit),
                Undo::SortedLen(sorted_len) => self.sorted_len = sorted_len,
                Undo::Key(slot, key) => self.keys[slot] = key,
                Undo::Relocated(handle, Some(key)) => {
                    self.relocated.insert(handle, key);
                }
                Undo::Relocated(handle, None) => {
                    self.relocated.remove(&handle);
                }
            }
        }
        self.reindex(removed_bit);
    }

    pub(super) fn reindex(&mut self, removed_bit: u64) {
        self.index = self
            .keys
            .iter()
            .enumerate()
            .skip(self.sorted_len)
            .filter(|(_, key)| **key & removed_bit == 0)
            .map(|(slot, key)| (*key, self.identities.handle(slot)))
            .chain(
                self.relocated
                    .iter()
                    .filter(|(_, key)| **key & removed_bit == 0)
                    .map(|(handle, key)| (*key, *handle)),
            )
            .collect();
    }

    pub(super) fn get(&self, handle: u32) -> Option<u64> {
        self.relocated
            .get(&handle)
            .copied()
            .or_else(|| self.keys.get(self.identities.slot(handle)?).copied())
    }
}
