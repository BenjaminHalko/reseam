// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use crate::types::access_flags::AccessFlags;
use crate::types::class::{ClassDef, NO_INDEX};
use crate::types::{StringIdx, TypeIdx};

pub(super) const RECORD_SIZE: usize = 32;

/// The four id fields of a `class_def_item`, readable for any class without
/// materializing it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ClassHeader {
    pub class_type: TypeIdx,
    pub access_flags: AccessFlags,
    pub superclass: Option<TypeIdx>,
    pub source_file: Option<StringIdx>,
}

impl ClassHeader {
    pub fn of(class: &ClassDef) -> Self {
        Self {
            class_type: class.class_type,
            access_flags: class.access_flags,
            superclass: class.superclass,
            source_file: class.source_file,
        }
    }
}

/// A `class_def_item` as stored in the file.
#[derive(Debug, Clone, Copy)]
pub struct RawClassDef {
    pub class_type: u32,
    pub access_flags: u32,
    pub superclass: u32,
    pub interfaces_off: u32,
    pub source_file: u32,
    pub annotations_off: u32,
    pub class_data_off: u32,
    pub static_values_off: u32,
}

impl RawClassDef {
    pub(super) fn read(buf: &[u8], off: usize) -> Self {
        let word = |i: usize| {
            let o = off + i * 4;
            u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]])
        };
        Self {
            class_type: word(0),
            access_flags: word(1),
            superclass: word(2),
            interfaces_off: word(3),
            source_file: word(4),
            annotations_off: word(5),
            class_data_off: word(6),
            static_values_off: word(7),
        }
    }

    pub fn header(&self) -> ClassHeader {
        ClassHeader {
            class_type: TypeIdx(self.class_type),
            access_flags: AccessFlags::from_bits_retain(self.access_flags),
            superclass: (self.superclass != NO_INDEX).then_some(TypeIdx(self.superclass)),
            source_file: (self.source_file != NO_INDEX).then_some(StringIdx(self.source_file)),
        }
    }
}
