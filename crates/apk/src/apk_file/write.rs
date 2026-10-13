// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{self, BufWriter};
use std::num::NonZeroUsize;
use std::path::{Path, PathBuf};

use reseam_dex::{DexPart, WriteLayout};
use tracing::{debug, info, instrument};

use super::baseline_profile::ProfileRewrite;
use super::component::EntryEdit;
use super::dex_workers::DexEntryStream;
use super::{ApkComponent, ApkFile, ComponentIndex, DexIndex, DexSource};
use crate::art_profile;
use crate::entry::{EntryName, is_signature_entry, next_free_dex_name};
use crate::error::{Result, invalid};
use crate::zip::{reader, writer};

#[derive(Debug, Clone, Copy)]
pub struct ApkWriteOptions {
    /// Treatment of v1 signature entries. Original v2/v3 signing blocks are
    /// never copied by the ZIP writer.
    pub signatures: SignaturePolicy,
    /// Threads serializing and deflating dirty DEX files concurrently. Each
    /// holds one DEX's writer state and pages through its source, so this
    /// bounds the write-phase memory. Past four, the write phase got no
    /// faster on large multi-DEX apps while its peak kept growing.
    pub dex_workers: NonZeroUsize,
    /// Deflate level for rewritten DEX entries. Level 3 compresses within two
    /// percent of level 6 in a quarter less time.
    pub dex_compression_level: i64,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum SignaturePolicy {
    #[default]
    Strip,
    /// Retains v1 entries verbatim, including signatures invalidated by edits.
    Preserve,
}

impl Default for ApkWriteOptions {
    fn default() -> Self {
        Self {
            signatures: SignaturePolicy::Strip,
            dex_workers: std::thread::available_parallelism()
                .unwrap_or(NonZeroUsize::MIN)
                .min(NonZeroUsize::MIN.saturating_add(3)),
            dex_compression_level: 3,
        }
    }
}

pub(super) struct DexMember {
    pub dex_index: DexIndex,
    pub part: Option<DexPart>,
}

pub(super) struct DexJob {
    pub members: Vec<DexMember>,
    pub component: ComponentIndex,
    pub name: EntryName,
}

/// A compressed single-entry ZIP holding a written DEX, with where each of
/// its members' pools and classes landed.
pub(super) struct WrittenDex {
    pub file: File,
    pub layouts: Vec<WriteLayout>,
}

enum EntrySource<'a> {
    Archive(usize),
    Edit(&'a EntryEdit),
    Dex(usize),
}

struct PlannedEntry<'a> {
    name: EntryName,
    source: EntrySource<'a>,
}

impl ApkFile {
    /// Writes complete component APKs under their input file names. Output is
    /// staged in temporary files before publication. Duplicate output names and
    /// paths pointing to the input are errors. Dirty state is retained, so the
    /// session can be written repeatedly.
    /// Edited v041 containers retain their physical entry and logical member
    /// order, including any pool-sized parts required by overflow.
    #[instrument(level = "info", skip_all)]
    pub fn write_to(
        &self,
        output_dir: impl AsRef<Path>,
        options: ApkWriteOptions,
    ) -> Result<Vec<PathBuf>> {
        let output_dir = output_dir.as_ref();
        std::fs::create_dir_all(output_dir)?;
        let names = self.output_names()?;
        let paths: Vec<_> = names.iter().map(|name| output_dir.join(name)).collect();
        for path in &paths {
            if path.exists() {
                let destination = reseam_storage::canonicalize(path)?;
                for component in &self.components {
                    if destination == reseam_storage::canonicalize(component.path())? {
                        return Err(invalid(
                            "apk output",
                            format!("{} is an input APK", path.display()),
                        ));
                    }
                }
            }
        }
        let mut staging = Vec::with_capacity(paths.len());
        self.write_components(options, |_| {
            let file = tempfile::NamedTempFile::new_in(output_dir)?;
            let output = file.reopen()?;
            staging.push(file);
            Ok(output)
        })?;
        for (file, path) in staging.into_iter().zip(&paths) {
            file.persist(path).map_err(|error| error.error)?;
        }
        Ok(paths)
    }

    /// Writes each component into an unlinked file in `dir`. The caller owns
    /// publication; the returned files are positioned at the end of the ZIP.
    #[instrument(level = "info", skip_all)]
    pub fn write_unsigned_files(
        &self,
        options: ApkWriteOptions,
        dir: &Path,
    ) -> Result<Vec<(String, File)>> {
        let names = self.output_names()?;
        let files = self.write_components(options, |_| tempfile::tempfile_in(dir))?;
        Ok(names.into_iter().zip(files).collect())
    }

    fn output_names(&self) -> Result<Vec<String>> {
        let mut used = HashSet::new();
        self.components
            .iter()
            .map(|component| {
                let name = component
                    .path()
                    .file_name()
                    .and_then(|name| name.to_str())
                    .ok_or_else(|| {
                        invalid(
                            "apk output",
                            format!("{} has no UTF-8 file name", component.path().display()),
                        )
                    })?;
                if !used.insert(name) {
                    return Err(invalid(
                        "apk output",
                        format!("duplicate output file name {name}"),
                    ));
                }
                Ok(name.to_string())
            })
            .collect()
    }

    fn write_components(
        &self,
        options: ApkWriteOptions,
        mut open: impl FnMut(usize) -> io::Result<File>,
    ) -> Result<Vec<File>> {
        let mut jobs = self.dex_jobs()?;
        jobs.sort_by_key(|job| {
            (
                job.component,
                self.components[job.component.0]
                    .archive()
                    .index_for_name(job.name.as_str())
                    .unwrap_or(usize::MAX),
            )
        });
        let profiles = (0..self.components.len())
            .map(|index| ProfileRewrite::new(self, ComponentIndex(index), &jobs))
            .collect::<Result<Vec<_>>>()?;
        let plans: Vec<_> = self
            .components
            .iter()
            .zip(&profiles)
            .enumerate()
            .map(|(index, (component, profile))| {
                plan(
                    component,
                    index,
                    &jobs,
                    options.signatures,
                    profile.is_some(),
                )
            })
            .collect();
        info!(
            dex_entry_count = self.dex.len(),
            dex_rewrite_count = jobs.len(),
            strip_signatures = options.signatures == SignaturePolicy::Strip,
            "serializing APK output"
        );
        std::thread::scope(|scope| {
            let mut entries = DexEntryStream::start(
                scope,
                &self.dex.dex_files,
                &jobs,
                options.dex_workers.get(),
                options.dex_compression_level,
            )?;
            plans
                .iter()
                .zip(profiles)
                .enumerate()
                .map(|(index, (plan, profile))| {
                    self.write_component(
                        ComponentIndex(index),
                        plan,
                        &jobs,
                        profile,
                        &mut entries,
                        open(index)?,
                    )
                })
                .collect()
        })
    }

    fn dex_jobs(&self) -> Result<Vec<DexJob>> {
        let rewritten: HashSet<_> = self
            .dex
            .iter()
            .zip(&self.dex_origins)
            .filter(|(dex, origin)| {
                origin.kind != DexSource::Removed
                    && (dex.is_dirty() || origin.kind == DexSource::Added)
            })
            .map(|(_, origin)| (origin.component, &origin.name))
            .collect();
        let mut used: Vec<HashSet<EntryName>> = self
            .components
            .iter()
            .map(|component| {
                component
                    .reserved_names()
                    .filter(|name| !matches!(component.edits().get(*name), Some(EntryEdit::Dex)))
                    .map(EntryName::from)
                    .collect()
            })
            .collect();
        let mut jobs = Vec::new();
        let mut emitted = HashSet::new();
        for (dex_index, (dex, origin)) in self.dex.iter().zip(&self.dex_origins).enumerate() {
            if origin.kind == DexSource::Removed
                || !self.components[origin.component.0].contains(origin.name.as_str())
                || !rewritten.contains(&(origin.component, &origin.name))
            {
                continue;
            }
            let component = origin.component;
            if !emitted.insert((component, &origin.name)) {
                continue;
            }
            let name = if origin.kind == DexSource::Added {
                next_free_dex_name(&mut used[component.0])
            } else {
                origin.name.clone()
            };
            if dex.header().version.is_container_format() {
                let members = self
                    .dex
                    .iter()
                    .zip(&self.dex_origins)
                    .enumerate()
                    .filter(|(_, (_, member))| {
                        member.kind != DexSource::Removed
                            && member.component == component
                            && member.name == origin.name
                    })
                    .map(|(index, (dex, _))| members_for(DexIndex(index), dex))
                    .collect::<Result<Vec<_>>>()?
                    .into_iter()
                    .flatten()
                    .collect();
                jobs.push(DexJob {
                    members,
                    component,
                    name,
                });
                continue;
            }
            for (ordinal, member) in members_for(DexIndex(dex_index), dex)?
                .into_iter()
                .enumerate()
            {
                let name = if ordinal == 0 {
                    name.clone()
                } else {
                    next_free_dex_name(&mut used[component.0])
                };
                jobs.push(DexJob {
                    members: vec![member],
                    component,
                    name,
                });
            }
        }
        Ok(jobs)
    }

    fn write_component(
        &self,
        index: ComponentIndex,
        plan: &[PlannedEntry<'_>],
        jobs: &[DexJob],
        mut profile: Option<ProfileRewrite>,
        entries: &mut DexEntryStream<'_>,
        output: File,
    ) -> Result<File> {
        let component = &self.components[index.0];
        debug!(component = component.name(), "writing APK component");
        let mut source = component.archive().clone();
        let mut writer = writer::Writer::new(BufWriter::with_capacity(1 << 20, output));
        for entry in plan {
            let result = match entry.source {
                EntrySource::Archive(index) => {
                    writer::copy_original(&mut writer, &mut source, index)
                }
                EntrySource::Dex(job) => entries.next().and_then(|written| {
                    let checksum = writer::copy_compressed(&mut writer, written.file)?;
                    if let Some(profile) = &mut profile {
                        profile.record(self, index, &jobs[job], &written.layouts, checksum);
                    }
                    Ok(())
                }),
                EntrySource::Edit(EntryEdit::Staged { file, compression }) => {
                    writer::write_file(&mut writer, entry.name.as_str(), file, compression.method())
                }
                EntrySource::Edit(EntryEdit::Manifest) => writer::write_bytes(
                    &mut writer,
                    entry.name.as_str(),
                    &component.manifest().serialize()?,
                    zip::CompressionMethod::Deflated,
                ),
                EntrySource::Edit(EntryEdit::Resources) => writer::write_file(
                    &mut writer,
                    entry.name.as_str(),
                    &component.resources_file()?,
                    zip::CompressionMethod::Stored,
                ),
                EntrySource::Edit(EntryEdit::Deleted | EntryEdit::Dex) => {
                    Err(invalid("apk write", "entry has no planned source"))
                }
            };
            result.map_err(|error| error.in_entry(entry.name.as_str()))?;
        }
        if let Some(profile) = profile {
            for (name, bytes) in profile.finish().to_entries()? {
                writer::write_bytes(&mut writer, name, &bytes, zip::CompressionMethod::Stored)
                    .map_err(|error| error.in_entry(name))?;
            }
        }
        Ok(writer
            .finish()?
            .into_inner()
            .map_err(io::IntoInnerError::into_error)?)
    }
}

fn members_for(index: DexIndex, dex: &reseam_dex::DexFile) -> Result<Vec<DexMember>> {
    Ok(match reseam_dex::split_to_fit(dex)? {
        None => vec![DexMember {
            dex_index: index,
            part: None,
        }],
        Some(parts) => parts
            .into_iter()
            .map(|part| DexMember {
                dex_index: index,
                part: Some(part),
            })
            .collect(),
    })
}

/// Rewritten baseline profiles are written last, once every DEX entry and
/// its checksum is known.
fn plan<'a>(
    component: &'a ApkComponent,
    index: usize,
    jobs: &[DexJob],
    signatures: SignaturePolicy,
    rewrites_profile: bool,
) -> Vec<PlannedEntry<'a>> {
    let dex_jobs: HashMap<_, _> = jobs
        .iter()
        .enumerate()
        .filter(|(_, job)| job.component == ComponentIndex(index))
        .map(|(job_index, job)| (&job.name, job_index))
        .collect();
    let names = component
        .archive()
        .file_names()
        .map(EntryName::from)
        .chain(
            component
                .edits()
                .iter()
                .filter(|(name, edit)| {
                    !matches!(edit, EntryEdit::Dex | EntryEdit::Deleted)
                        && !dex_jobs.contains_key(name)
                        && !reader::contains(component.archive(), name.as_str())
                })
                .map(|(name, _)| name.clone()),
        )
        .chain(
            jobs.iter()
                .filter(|job| {
                    job.component == ComponentIndex(index)
                        && !reader::contains(component.archive(), job.name.as_str())
                })
                .map(|job| job.name.clone()),
        );
    names
        .filter_map(|name| {
            if matches!(
                component.edits().get(name.as_str()),
                Some(EntryEdit::Deleted)
            ) || (signatures == SignaturePolicy::Strip && is_signature_entry(name.as_str()))
                || (rewrites_profile && art_profile::ENTRIES.contains(&name.as_str()))
            {
                return None;
            }
            let source = if let Some(&job) = dex_jobs.get(&name) {
                EntrySource::Dex(job)
            } else if let Some(edit) = component.edits().get(name.as_str()) {
                EntrySource::Edit(edit)
            } else {
                EntrySource::Archive(
                    component
                        .archive()
                        .index_for_name(name.as_str())
                        .expect("name came from the archive"),
                )
            };
            Some(PlannedEntry { name, source })
        })
        .collect()
}
