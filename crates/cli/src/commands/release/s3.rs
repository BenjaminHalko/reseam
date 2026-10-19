// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::fs::File;
use std::path::Path;
use std::time::Duration;

use anyhow::{Context, Result};
use rusty_s3::actions::ListObjectsV2;
use rusty_s3::{Bucket, Credentials, S3Action, UrlStyle};
use ureq::Agent;
use url::Url;

use super::http::{agent, checked, text_if_found};

const SIGNATURE_LIFETIME: Duration = Duration::from_secs(600);

pub(super) struct S3 {
    bucket: Bucket,
    credentials: Credentials,
    agent: Agent,
}

impl S3 {
    pub fn from_env(bucket: &str) -> Result<Self> {
        let endpoint = Url::parse(&env("AWS_ENDPOINT_URL")?).context("invalid AWS_ENDPOINT_URL")?;
        Ok(Self {
            bucket: Bucket::new(
                endpoint,
                UrlStyle::Path,
                bucket.to_owned(),
                env("AWS_REGION")?,
            )
            .context("invalid S3 bucket")?,
            credentials: Credentials::new(env("AWS_ACCESS_KEY_ID")?, env("AWS_SECRET_ACCESS_KEY")?),
            agent: agent(),
        })
    }

    pub fn get(&self, key: &str) -> Result<Option<String>> {
        let url = self
            .bucket
            .get_object(Some(&self.credentials), key)
            .sign(SIGNATURE_LIFETIME);
        text_if_found(self.agent.get(url.as_str()).call())
    }

    pub fn put(&self, key: &str, path: &Path, caching: Caching) -> Result<()> {
        let url = self
            .bucket
            .put_object(Some(&self.credentials), key)
            .sign(SIGNATURE_LIFETIME);
        let file =
            File::open(path).with_context(|| format!("failed to open {}", path.display()))?;
        checked(
            self.agent
                .put(url.as_str())
                .header("Cache-Control", caching.cache_control())
                .header("Content-Type", content_type(path))
                .send(file),
        )?;
        Ok(())
    }

    pub fn folders(&self, prefix: &str) -> Result<Vec<String>> {
        let mut folders = Vec::new();
        self.list(prefix, Some("/"), |page| {
            folders.extend(page.common_prefixes.into_iter().filter_map(|common| {
                common
                    .prefix
                    .strip_prefix(prefix)
                    .map(|name| name.trim_end_matches('/').to_owned())
            }));
        })?;
        Ok(folders)
    }

    pub fn delete_all(&self, prefix: &str) -> Result<()> {
        let mut keys = Vec::new();
        self.list(prefix, None, |page| {
            keys.extend(page.contents.into_iter().map(|object| object.key));
        })?;
        for key in keys {
            let url = self
                .bucket
                .delete_object(Some(&self.credentials), &key)
                .sign(SIGNATURE_LIFETIME);
            checked(self.agent.delete(url.as_str()).call())?;
        }
        Ok(())
    }

    fn list(
        &self,
        prefix: &str,
        delimiter: Option<&str>,
        mut page: impl FnMut(rusty_s3::actions::ListObjectsV2Response),
    ) -> Result<()> {
        let mut token: Option<String> = None;
        loop {
            let mut action: ListObjectsV2<'_> =
                self.bucket.list_objects_v2(Some(&self.credentials));
            action.with_prefix(prefix);
            if let Some(delimiter) = delimiter {
                action.with_delimiter(delimiter);
            }
            if let Some(token) = &token {
                action.with_continuation_token(token.clone());
            }
            let url = action.sign(SIGNATURE_LIFETIME);
            let text = checked(self.agent.get(url.as_str()).call())?
                .into_body()
                .read_to_string()?;
            let mut response =
                ListObjectsV2::parse_response(&text).context("S3 returned an invalid listing")?;
            token = response.next_continuation_token.take();
            page(response);
            if token.is_none() {
                return Ok(());
            }
        }
    }
}

#[derive(Clone, Copy)]
pub(super) enum Caching {
    Release,
    Latest,
}

impl Caching {
    fn cache_control(self) -> &'static str {
        match self {
            Self::Release => "public, max-age=86400",
            Self::Latest => "no-cache",
        }
    }
}

fn content_type(path: &Path) -> &'static str {
    if path
        .extension()
        .is_some_and(|extension| extension == "json")
    {
        "application/json"
    } else {
        "application/octet-stream"
    }
}

fn env(name: &str) -> Result<String> {
    std::env::var(name)
        .with_context(|| format!("{name} is not set; S3 targets read the AWS_* variables"))
}
