// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use crate::{OptionDeclaration, Problem};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[boltffi::data]
pub struct CompatiblePackage {
    pub package: String,
    /// Accepted version names. With `version_codes` also empty, every version.
    pub versions: Vec<String>,
    /// Accepted version codes; an APK matches when its name or its code is listed.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub version_codes: Vec<i32>,
}

impl CompatiblePackage {
    fn every_version(&self) -> bool {
        self.versions.is_empty() && self.version_codes.is_empty()
    }
}

/// The version an APK's manifest declares.
#[derive(Debug, Clone, Copy)]
pub struct ApkVersion<'a> {
    pub name: Option<&'a str>,
    pub code: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
#[boltffi::data]
pub enum Compatibility {
    /// Declares no package, so it applies to every app and joins presets only when enabled by default.
    Universal,
    Packages {
        packages: Vec<CompatiblePackage>,
    },
}

impl Compatibility {
    pub fn is_universal(&self) -> bool {
        matches!(self, Self::Universal)
    }
}

impl FromIterator<CompatiblePackage> for Compatibility {
    fn from_iter<I: IntoIterator<Item = CompatiblePackage>>(packages: I) -> Self {
        let packages: Vec<_> = packages.into_iter().collect();
        if packages.is_empty() {
            Self::Universal
        } else {
            Self::Packages { packages }
        }
    }
}

/// A bulk selection clients offer next to toggling patches one by one.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[boltffi::data]
pub enum PatchPreset {
    /// The patches their authors enable by default.
    #[default]
    Recommended,
    All,
    None,
}

impl std::str::FromStr for PatchPreset {
    type Err = Problem;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "recommended" => Ok(Self::Recommended),
            "all" => Ok(Self::All),
            "none" => Ok(Self::None),
            _ => Err(Problem::UnknownPreset {
                value: value.to_owned(),
            }),
        }
    }
}

/// A nonempty slug: lowercase letters or digits separated by single hyphens.
pub fn is_slug(value: &str) -> bool {
    !value.is_empty()
        && !value.starts_with('-')
        && !value.ends_with('-')
        && !value.contains("--")
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[boltffi::data]
pub struct PatchSpec {
    pub bundle: String,
    /// `<package>.<property>` of the public declaration; unique within the bundle.
    pub id: String,
    /// What users see. Different patches may share a display name.
    pub name: String,
    /// Hidden patches are dependencies of other patches: never listed to
    /// users and never selected on their own.
    pub hidden: bool,
    pub description: String,
    pub enabled_by_default: bool,
    /// References (`<bundle>/<id>`) to the patches that run first.
    pub dependencies: Vec<String>,
    pub compatibility: Compatibility,
    pub options: Vec<OptionDeclaration>,
    /// Replaces the APK files the engine writes, before they are signed. A mount
    /// build leaves such a patch out, since it cannot tell what the files hold.
    #[serde(default)]
    #[boltffi::default(false)]
    pub rewrites_output: bool,
}

impl PatchSpec {
    pub fn reference(&self) -> String {
        format!("{}/{}", self.bundle, self.id)
    }

    pub fn incompatibility(
        &self,
        package: Option<&str>,
        version: ApkVersion<'_>,
    ) -> Option<String> {
        let entries = match self.package_compatibility(package) {
            Ok(Some(entries)) => entries,
            Ok(None) => return None,
            Err(reason) => return Some(reason.to_string()),
        };
        let entries: Vec<&CompatiblePackage> = entries
            .iter()
            .filter(|entry| Some(entry.package.as_str()) == package)
            .collect();
        if entries.iter().any(|entry| entry.every_version()) {
            return None;
        }
        let names: Vec<&str> = entries
            .iter()
            .flat_map(|entry| entry.versions.iter().map(String::as_str))
            .collect();
        let codes: Vec<i32> = entries
            .iter()
            .flat_map(|entry| entry.version_codes.iter().copied())
            .collect();
        if version.name.is_some_and(|name| names.contains(&name))
            || version.code.is_some_and(|code| codes.contains(&code))
        {
            return None;
        }
        let mut expected = Vec::new();
        if !names.is_empty() {
            expected.push(format!("version name [{}]", names.join(", ")));
        }
        if !codes.is_empty() {
            let codes: Vec<String> = codes.iter().map(i32::to_string).collect();
            expected.push(format!("version code [{}]", codes.join(", ")));
        }
        let got = match (version.name, version.code) {
            (Some(name), Some(code)) => format!("{name} ({code})"),
            (Some(name), None) => name.to_owned(),
            (None, Some(code)) => format!("version code {code}"),
            (None, None) => "an APK with no version".to_owned(),
        };
        Some(format!("expected {}, got {got}", expected.join(" or ")))
    }

    /// Whether `preset` selects the patch for an app of `package`. Presets take
    /// the patches that declare the package, and a universal patch only when its
    /// author enables it by default. Version compatibility is not considered; it
    /// is reported separately.
    pub fn in_preset(&self, preset: PatchPreset, package: Option<&str>) -> bool {
        let declared = matches!(self.package_compatibility(package), Ok(Some(_)));
        let universal = matches!(self.compatibility, Compatibility::Universal);
        !self.hidden
            && match preset {
                PatchPreset::Recommended => (declared || universal) && self.enabled_by_default,
                PatchPreset::All => declared || universal && self.enabled_by_default,
                PatchPreset::None => false,
            }
    }

    pub fn package_incompatibility(&self, package: Option<&str>) -> Option<String> {
        self.package_compatibility(package)
            .err()
            .map(|error| error.to_string())
    }

    fn package_compatibility(
        &self,
        package: Option<&str>,
    ) -> crate::Result<Option<&[CompatiblePackage]>> {
        let Compatibility::Packages { packages } = &self.compatibility else {
            return Ok(None);
        };
        let Some(package) = package else {
            return Err(Problem::MissingPackage);
        };
        if !packages.iter().any(|entry| entry.package == package) {
            return Err(Problem::IncompatiblePackage {
                package: package.to_owned(),
            });
        }
        Ok(Some(packages))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spec(compatibility: Compatibility, enabled_by_default: bool, hidden: bool) -> PatchSpec {
        PatchSpec {
            bundle: "bundle".to_owned(),
            id: "patch".to_owned(),
            name: "Patch".to_owned(),
            hidden,
            description: String::new(),
            enabled_by_default,
            dependencies: Vec::new(),
            compatibility,
            options: Vec::new(),
            rewrites_output: false,
        }
    }

    fn presets(spec: &PatchSpec, package: Option<&str>) -> Vec<PatchPreset> {
        [
            PatchPreset::Recommended,
            PatchPreset::All,
            PatchPreset::None,
        ]
        .into_iter()
        .filter(|&preset| spec.in_preset(preset, package))
        .collect()
    }

    #[test]
    fn presets_take_visible_patches_that_declare_the_package_or_opt_in() {
        use PatchPreset::{All, Recommended};
        let declared = |versions: &[&str]| {
            [CompatiblePackage {
                package: "com.example".to_owned(),
                versions: versions.iter().map(|v| (*v).to_owned()).collect(),
                version_codes: Vec::new(),
            }]
            .into_iter()
            .collect()
        };
        let app = Some("com.example");

        assert_eq!(
            presets(&spec(declared(&[]), true, false), app),
            vec![Recommended, All]
        );
        assert_eq!(
            presets(&spec(declared(&["1.0"]), true, false), app),
            vec![Recommended, All]
        );
        assert_eq!(presets(&spec(declared(&[]), false, false), app), vec![All]);
        assert_eq!(
            presets(&spec(declared(&[]), true, false), Some("com.other")),
            vec![]
        );
        assert_eq!(presets(&spec(declared(&[]), true, false), None), vec![]);
        assert_eq!(presets(&spec(declared(&[]), false, true), app), vec![]);
        assert_eq!(
            presets(&spec(Compatibility::Universal, true, false), app),
            vec![Recommended, All]
        );
        assert_eq!(
            presets(&spec(Compatibility::Universal, false, false), app),
            vec![]
        );
        assert_eq!(
            presets(&spec(Compatibility::Universal, true, true), app),
            vec![]
        );
    }

    #[test]
    fn pinned_versions_match_by_name_or_code() {
        let pinned = |versions: &[&str], version_codes: &[i32]| {
            spec(
                [CompatiblePackage {
                    package: "com.example".to_owned(),
                    versions: versions.iter().map(|v| (*v).to_owned()).collect(),
                    version_codes: version_codes.to_vec(),
                }]
                .into_iter()
                .collect(),
                true,
                false,
            )
        };
        let version = |name, code| ApkVersion { name, code };
        let app = Some("com.example");
        for (spec, version, compatible) in [
            (pinned(&[], &[]), version(None, None), true),
            (pinned(&["1.0"], &[]), version(Some("1.0"), Some(5)), true),
            (pinned(&["1.0"], &[]), version(Some("2.0"), Some(5)), false),
            (pinned(&[], &[5]), version(Some("2.0"), Some(5)), true),
            (pinned(&[], &[5]), version(Some("2.0"), Some(6)), false),
            (pinned(&[], &[5]), version(None, None), false),
            (pinned(&["1.0"], &[5]), version(Some("1.0"), Some(6)), true),
            (pinned(&["1.0"], &[5]), version(Some("2.0"), Some(5)), true),
            (pinned(&["1.0"], &[5]), version(Some("2.0"), Some(6)), false),
        ] {
            assert_eq!(
                spec.incompatibility(app, version).is_none(),
                compatible,
                "{:?} against {version:?}",
                spec.compatibility
            );
        }
    }
}
