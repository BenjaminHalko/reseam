// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::fs::File;
use std::io;

use reseam_storage::file::FileExt;

use super::records::Method;

/// How the writer encodes data it is given uncompressed.
#[derive(Debug, Clone, Copy)]
pub(crate) enum Encoding {
    Stored,
    Deflated { level: u32 },
}

impl Encoding {
    /// Deflated at zlib's default level.
    pub(crate) const DEFLATED: Self = Self::Deflated { level: 6 };

    pub(super) fn method(self) -> Method {
        match self {
            Self::Stored => Method::Stored,
            Self::Deflated { .. } => Method::Deflated,
        }
    }
}

/// Entry data the writer reads by offset, from any thread.
pub(crate) trait Source: Sync {
    fn len(&self) -> io::Result<u64>;
    fn read_into(&self, buf: &mut [u8], offset: u64) -> io::Result<()>;
}

impl Source for File {
    fn len(&self) -> io::Result<u64> {
        Ok(self.metadata()?.len())
    }

    fn read_into(&self, buf: &mut [u8], offset: u64) -> io::Result<()> {
        self.read_exact_at(buf, offset)
    }
}

impl Source for [u8] {
    fn len(&self) -> io::Result<u64> {
        Ok(<[u8]>::len(self) as u64)
    }

    fn read_into(&self, buf: &mut [u8], offset: u64) -> io::Result<()> {
        let start = usize::try_from(offset).map_err(|_| io::ErrorKind::UnexpectedEof)?;
        let data = self
            .get(start..start + buf.len())
            .ok_or(io::ErrorKind::UnexpectedEof)?;
        buf.copy_from_slice(data);
        Ok(())
    }
}
