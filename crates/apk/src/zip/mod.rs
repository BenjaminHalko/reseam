// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

pub(crate) mod reader;
pub(crate) mod writer;

/// Bytes moved per read when copying entry data. Large entries copied in
/// small reads spend most of their time in system calls.
pub(crate) const COPY_BUFFER: usize = 1 << 20;
