// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use rayon::prelude::*;

use super::records::RECORD_SIZE;
use super::{ClassSlot, ClassTable, RawClassDef};
use crate::error::{Result, index_out_of_bounds};
use crate::read::class::read_class_def;
use crate::types::class::ClassDef;
use crate::types::header::ParseOptions;

impl ClassTable {
    /// Decodes the class from the file if needed and returns it mutably.
    pub fn materialize(&mut self, i: usize, opts: ParseOptions) -> Result<&mut ClassDef> {
        let len = self.len();
        let Some(slot) = self.slots.get_mut(i) else {
            return Err(index_out_of_bounds("class", i as u32, len as u32));
        };
        if let ClassSlot::Raw(r) = *slot {
            let buf = self.raw_bytes();
            let def = read_class_def(
                self.raw.as_ref().expect("raw slot retains its source"),
                RawClassDef::read(buf, self.off + r as usize * RECORD_SIZE),
                opts,
            )?;
            self.slots[i] = ClassSlot::resident(def);
            let pos = self.resident.partition_point(|&r| (r as usize) < i);
            self.resident.insert(pos, i as u32);
        }
        Ok(self
            .resident_mut(i)
            .expect("materialization installs a resident class"))
    }

    pub fn materialize_all(&mut self, opts: ParseOptions) -> Result<()> {
        let Some(raw) = self.raw.clone() else {
            return Ok(());
        };
        let buf = raw.as_bytes();
        let off = self.off;
        self.slots
            .par_iter_mut()
            .try_for_each(|slot| -> Result<()> {
                if let ClassSlot::Raw(r) = *slot {
                    let def = read_class_def(
                        &raw,
                        RawClassDef::read(buf, off + r as usize * RECORD_SIZE),
                        opts,
                    )?;
                    *slot = ClassSlot::resident(def);
                }
                Ok(())
            })?;
        self.resident = (0..self.slots.len() as u32).collect();
        Ok(())
    }

    pub fn push(&mut self, class: ClassDef) -> usize {
        self.slots.push(ClassSlot::resident(class));
        self.by_type.take();
        let i = self.slots.len() - 1;
        self.resident.push(i as u32);
        i
    }

    pub fn remove(&mut self, i: usize, opts: ParseOptions) -> Result<ClassDef> {
        self.materialize(i, opts)?;
        self.by_type.take();
        let pos = self.resident.partition_point(|&r| (r as usize) < i);
        self.resident.remove(pos);
        self.resident[pos..].iter_mut().for_each(|r| *r -= 1);
        match self.slots.remove(i) {
            ClassSlot::Resident(c) => Ok(c.def),
            ClassSlot::Raw(_) => {
                unreachable!("materialization has replaced the raw slot with a resident class")
            }
        }
    }

    pub fn into_defs(mut self, opts: ParseOptions) -> Result<Vec<ClassDef>> {
        self.materialize_all(opts)?;
        Ok(self
            .slots
            .into_iter()
            .map(|slot| match slot {
                ClassSlot::Resident(c) => c.def,
                ClassSlot::Raw(_) => {
                    unreachable!("materialization has replaced the raw slot with a resident class")
                }
            })
            .collect())
    }
}
