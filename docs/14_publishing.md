---
description: Release a bundle so users can add it to Reseam Manager and get updates.
---

# Publishing

A published bundle is two files on any static host:

- the `.reseam` bundle,
- `patches.json`, an index listing your releases, their download links, and your public key.

Users add your bundle from a link you share. Reseam Manager then checks `patches.json` for new releases and updates the bundle on its own.

## Release with the template

The template's `.github/workflows/release.yml` publishes a release whenever you push a tag that starts with `v`. It runs on GitHub and on Forgejo, including Codeberg.

1. Add your signing key as the repository secret `BUNDLE_SIGNING_KEY_B64`:

   ```bash
   base64 -w0 ~/.reseam/bundle-signing.key
   ```

2. On Forgejo, set the repository variable `RUNNER` to the label of a runner with Java and Node.js. GitHub uses `ubuntu-latest`.

3. Push a tag:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

The workflow builds and signs the bundle with the CLI that matches your plugin version, then runs [`reseam release patches`](/docs/cli/release/). That adds the release to the `patches.json` of your latest release, with the commit subjects since the previous tag as its notes, and publishes both on a release whose notes start with your share link.

## Release by hand

`reseam release` also publishes to an S3-compatible bucket or a folder for any static host:

```bash
./gradlew bundle -PreleaseTag=v0.1.0
reseam release patches build/reseam/my-patches.reseam --tag v0.1.0 \
  --to ./site --public-url https://example.com/bundles
```

It reads the `patches.json` already in the target, adds the release, and writes the bundle to `<target>/v0.1.0/` and the index to `<target>/patches.json`. Upload the folder as it is: each release keeps its own folder and URL. See [`reseam release`](/docs/cli/release/) for S3 and every flag.

## Share your bundle

Share this link, with your repository page or the URL of your `patches.json` encoded into it:

```text
https://reseam.app/add/?url=<encoded patches.json URL>
```

On Android, the link opens Reseam Manager, which shows the bundle's apps, patches and key and asks before adding it. Without Reseam Manager, or on a computer, it opens a page with the steps and a download link. The link only carries your URL: Reseam Manager downloads `patches.json` and the bundle from your host.

Releases from the template list this link already. To encode a URL yourself:

```bash
jq -rn --arg url https://example.com/bundles/patches.json '$url | @uri'
```

Users can also paste the `patches.json` URL into Add patch source in Reseam Manager. For a repository address, Reseam Manager looks for `patches.json` in the repository's latest release, at the paths GitHub, Forgejo, Gitea and GitLab serve release files from.

## Your key

Users trust your public key, not your name. When they add your bundle, Reseam Manager shows the key and asks before trusting it.

- Publish your public key somewhere users already trust you, such as your repository's README, so they can compare it.
- Back up the key file. A bundle signed with a new key asks every user to trust it again.
- `reseam release patches` refuses to change the key recorded in a published `patches.json`.

## When Reseam updates

Bundles load on engines of the same release line. When a new engine line comes out, bump the plugin version in `settings.gradle.kts`, rebuild, and release. Until you do, the newer Reseam Manager can't load your bundle.
