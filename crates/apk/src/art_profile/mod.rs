// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Baseline profiles: the `assets/dexopt` files AGP builds into an APK, which
//! name the classes and methods ART compiles ahead of time, and the copy in
//! ART's own format that ART reads from the APK itself.

mod dex_metadata;
mod read;
mod rewrite;
mod write;

use std::collections::BTreeMap;

pub use dex_metadata::dex_metadata;

pub(crate) const PROFILE_ENTRY: &str = "assets/dexopt/baseline.prof";
pub(crate) const METADATA_ENTRY: &str = "assets/dexopt/baseline.profm";
/// Read by ART from Android 15 when no other profile matches the installed DEX.
pub(crate) const EMBEDDED_ENTRY: &str = "assets/art-profile/baseline.prof";
/// The entries [`BaselineProfile::to_entries`] writes.
pub(crate) const ENTRIES: [&str; 3] = [PROFILE_ENTRY, METADATA_ENTRY, EMBEDDED_ENTRY];

bitflags::bitflags! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub(crate) struct MethodFlags: u16 {
        const HOT = 1;
        const STARTUP = 1 << 1;
        const POST_STARTUP = 1 << 2;
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BaselineProfile {
    pub dex: Vec<DexProfile>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DexProfile {
    /// The DEX entry name, such as `classes2.dex`.
    pub name: String,
    /// CRC-32 of the DEX entry. ART ignores a profile whose checksum differs
    /// from the installed DEX.
    pub checksum: u32,
    pub method_count: u32,
    /// Type indices of the profiled classes, ascending.
    pub classes: Vec<u32>,
    pub methods: BTreeMap<u32, MethodFlags>,
    /// From `baseline.profm`; ART formats from Android 12 need the type count.
    pub metadata: Option<DexMetadata>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DexMetadata {
    pub type_count: u32,
    /// Class definition indices of the profiled classes, ascending.
    pub class_defs: Vec<u32>,
}
