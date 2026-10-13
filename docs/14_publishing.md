---
description: Release a bundle so users can add it to Reseam Manager and get updates.
---

# Publishing

A published bundle is two files on any static host:

- the `.reseam` bundle,
- `patches.json`, an index listing your releases, their download links, and your public key.

Users add your bundle from a link you share. Reseam Manager then checks `patches.json` for new releases and updates the bundle on its own.

## Release with the template

The template's `.github/workflows/release.yml` publishes a GitHub release whenever you push a tag that starts with `v`:

1. Add your signing key as the repository secret `BUNDLE_SIGNING_KEY_B64`:

   ```bash
   base64 -w0 ~/.reseam/bundle-signing.key
   ```

2. Push a tag:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

The workflow downloads the CLI that matches your plugin version and the `patches.json` of your latest release, builds and signs the bundle, adds the release to `patches.json` with the commit subjects since the previous tag as its notes, and attaches both to the release. The release notes start with your share link.

## Release by hand

```bash
./gradlew bundle -PreleaseTag=v0.1.0
reseam publish patches build/reseam/my-patches.reseam \
  --url https://example.com/my-patches-0.1.0.reseam
```

This adds the release to `patches.json` in the current folder, or creates it. Keep that file between releases: it is your release history. Pass `--description-file <path>` to add release notes. Upload both files, the bundle to the URL you gave. Each release keeps its own URL; don't overwrite an old bundle file. See [`reseam publish`](/docs/cli/publish/) for every flag.

## Share your bundle

Share this link, with the URL of your `patches.json` encoded into it:

```text
https://reseam.app/add/?url=<encoded patches.json URL>
```

On Android, the link opens Reseam Manager, which shows the bundle's apps, patches and key and asks before adding it. Without Reseam Manager, or on a computer, it opens a page with the steps and a download link. The link only carries your URL: Reseam Manager downloads `patches.json` and the bundle from your host.

To encode the URL:

```bash
jq -rn --arg url https://github.com/<owner>/<repo>/releases/latest/download/patches.json '$url | @uri'
```

Users can also paste the `patches.json` URL into Add patch source in Reseam Manager. For a repository address, Reseam Manager looks for `patches.json` in the repository's latest release, at the paths GitHub, Forgejo, Gitea and GitLab serve release files from.

## Your key

Users trust your public key, not your name. When they add your bundle, Reseam Manager shows the key and asks before trusting it.

- Publish your public key somewhere users already trust you, such as your repository's README, so they can compare it.
- Back up the key file. A bundle signed with a new key asks every user to trust it again.
- `reseam publish patches` refuses to change the key recorded in an existing `patches.json`.

## When Reseam updates

Bundles load on engines of the same release line. When a new engine line comes out, bump the plugin version in `settings.gradle.kts`, rebuild, and release. Until you do, the newer Reseam Manager can't load your bundle.
