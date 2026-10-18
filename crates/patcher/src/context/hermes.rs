// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::collections::BTreeMap;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use reseam_apk::Compression;
use reseam_hermes::{
    Code, Editor, Edits, FunctionId, FunctionIndex, HermesFile, HermesImage, HookKind, Import,
    ModuleId,
};
use reseam_storage::Bytes;

use super::PatchContext;
use crate::error::{PatcherError, Result};

/// The React Native bundle in the base APK.
const BUNDLE: &str = "assets/index.android.bundle";

pub(super) struct HermesSession {
    image: HermesImage,
    index: FunctionIndex,
    edits: Option<Edits>,
    modules: BTreeMap<ModuleSource, ModuleId>,
}

#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(super) enum ModuleSource {
    AppRuntime,
    File(PathBuf),
}

impl HermesSession {
    pub(super) fn open_savepoint(&mut self, savepoint: reseam_journal::Savepoint) {
        self.edits_mut().open_savepoint(savepoint);
    }

    pub(super) fn release_savepoint(&mut self, savepoint: reseam_journal::Savepoint) {
        self.edits_mut().release_savepoint(savepoint);
    }

    pub(super) fn rollback_to(&mut self, savepoint: reseam_journal::Savepoint) {
        self.edits_mut().rollback_to(savepoint);
    }

    pub(super) fn forget_module(&mut self, source: &ModuleSource) {
        self.modules.remove(source);
    }

    fn edits_mut(&mut self) -> &mut Edits {
        self.edits
            .as_mut()
            .expect("a session holds its edits outside an edit")
    }

    fn edit<T>(
        &mut self,
        action: impl FnOnce(&mut Editor<'_>, &FunctionIndex) -> Result<T>,
    ) -> Result<T> {
        let file = self.image.file();
        let edits = self
            .edits
            .take()
            .expect("a session holds its edits outside an edit");
        let mut editor = Editor::resume(file, edits)?;
        let outcome = action(&mut editor, &self.index);
        self.edits = Some(editor.into_edits());
        outcome
    }
}

impl PatchContext<'_> {
    fn hermes_session(&mut self) -> Result<&mut HermesSession> {
        if self.hermes.is_none() {
            let source = self.apk.map_component_entry(0, BUNDLE)?.ok_or_else(|| {
                PatcherError::NotFound(format!("base APK Hermes bundle {BUNDLE}"))
            })?;
            let image = HermesImage::parse(Bytes::from_mmap(Arc::new(source)))?;
            let index = FunctionIndex::build(&image.file())?;
            let mut edits = Editor::new(image.file()).into_edits();
            self.journal
                .savepoints()
                .for_each(|savepoint| edits.open_savepoint(savepoint));
            self.hermes = Some(HermesSession {
                image,
                index,
                edits: Some(edits),
                modules: BTreeMap::new(),
            });
        }
        Ok(self.hermes.as_mut().expect("Hermes opened on first use"))
    }

    pub(crate) fn hermes_version(&mut self) -> Result<u32> {
        Ok(self.hermes_session()?.image.file().version())
    }

    pub(crate) fn hermes_find(
        &mut self,
        name: Option<&str>,
        strings: &[&str],
        parameters: Option<u32>,
    ) -> Result<FunctionId> {
        let session = self.hermes_session()?;
        Ok(session
            .index
            .find_function(&session.image.file(), name, strings, parameters)?)
    }

    /// Links a bundle's JavaScript extension compiled to Hermes bytecode, with every app module
    /// it imports resolved.
    pub(crate) fn hermes_link(&mut self, module: &Path, imports: &[Import]) -> Result<ModuleId> {
        self.hermes_link_source(ModuleSource::File(module.to_owned()), imports, |_| {
            extension_bytes(module)
        })
    }

    /// Links the app runtime's JavaScript, compiled for the app's bytecode version.
    #[cfg(feature = "bridge")]
    pub(crate) fn hermes_link_app_runtime(&mut self) -> Result<ModuleId> {
        self.hermes_link_source(ModuleSource::AppRuntime, &[], |version| {
            crate::app_runtime::hermes(version)
                .map(Bytes::from_slice)
                .ok_or_else(|| {
                    PatcherError::NotFound(format!(
                        "the app runtime's JavaScript for Hermes bytecode version {version}"
                    ))
                })
        })
    }

    fn hermes_link_source(
        &mut self,
        source: ModuleSource,
        imports: &[Import],
        bytes: impl FnOnce(u32) -> Result<Bytes>,
    ) -> Result<ModuleId> {
        let session = self.hermes_session()?;
        if let Some(&id) = session.modules.get(&source) {
            return Ok(id);
        }
        let module = bytes(session.image.file().version())?;
        let module_file = HermesFile::parse(&module)?;
        let id = session.edit(|editor, index| Ok(editor.link(index, &module_file, imports)?))?;
        session.modules.insert(source.clone(), id);
        self.journal
            .record(super::undo::Part::Module(source.clone()), || {
                super::undo::Undo::Module(source)
            });
        Ok(id)
    }

    pub(crate) fn hermes_hook(
        &mut self,
        function: FunctionId,
        kind: HookKind,
        code: &Code,
    ) -> Result<()> {
        self.hermes_session()?
            .edit(|editor, index| Ok(editor.hook(index, function, kind, code)?))
    }

    /// Writes the bundle back when a patch changed it.
    pub(crate) fn finish_hermes(&mut self) -> Result<()> {
        let Some(session) = &mut self.hermes else {
            return Ok(());
        };
        if session.edits.as_ref().is_none_or(Edits::is_empty) {
            return Ok(());
        }
        let mut file = reseam_storage::temporary_file()?;
        session.edit(|editor, _| {
            let mut output = BufWriter::new(&mut file);
            editor.write(&mut output)?;
            output.flush()?;
            Ok(())
        })?;
        self.apk
            .inject_file_spooled(0, BUNDLE, file, Compression::Stored)?;
        Ok(())
    }
}

/// The app modules a bundle's JavaScript extension imports, by specifier.
pub(crate) fn hermes_imports(module: &Path) -> Result<Vec<String>> {
    let bytes = extension_bytes(module)?;
    Ok(reseam_hermes::imports(&HermesFile::parse(&bytes)?)?)
}

fn extension_bytes(module: &Path) -> Result<Bytes> {
    let file = std::fs::File::open(module)
        .map_err(|e| PatcherError::Bundle(format!("Hermes extension {}: {e}", module.display())))?;
    // SAFETY: bundle payloads are validated immutable snapshots retained for the run.
    Ok(Bytes::from_mmap(Arc::new(unsafe {
        reseam_storage::map_file(&file)?
    })))
}
