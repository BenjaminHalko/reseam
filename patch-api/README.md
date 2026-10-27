<p align="center">
  <img src="https://reseam.app/logo.svg" alt="Reseam logo" width="96">
</p>

<h1 align="center">Reseam Patch API</h1>

The Kotlin API patches are written against, published as `app.reseam:reseam-patch-sdk`.

To write patches, start from the [patch bundle template](https://git.reseam.app/reseam/patches-template) and follow the [docs](https://reseam.app/docs/authoring/start/). This README is for people changing the API itself.

## Layout

| Package | Contents |
|---|---|
| `app.reseam.patch` | What patch authors use, one concern per file. `Patch.kt` and `Options.kt` declare patches. `MethodTargets.kt`, `ClassTargets.kt`, `FieldTargets.kt`, `QueryDsl.kt`, `Points.kt`, `PointTargets.kt`, and `PointDsl.kt` find code. `Code.kt`, `Hooks.kt`, `Extensions.kt`, and `CallRedirects.kt` change it. `ManifestScope.kt`, `ResourceScope.kt`, `FileScope.kt`, `Xml.kt`, and `BytecodeScope.kt` cover the rest of the APK. `Bindings.kt` reads obfuscated objects. `Hermes.kt`, `JsFunctions.kt`, `JsCode.kt`, and `JsExtensions.kt` cover Hermes. |
| `app.reseam.patch.settings` | Settings, the settings host, gates, and brands. |
| `app.reseam.patch.dex` | Raw bytecode access: `Method`, `DexClass`, `Opcode`, `InstructionBuilder`. |
| `app.reseam.patch.types` | Generated data types such as `Instruction` and `MethodRef`. Authors use these too. |
| `app.reseam.patch.native` | Generated calls into the engine, all `internal`. Only the handwritten code uses them. |

The internals behind the public files live in `run/` (the running patch), `search/` (target queries), `points/` (instruction matching), `emit/` (code blocks, recorded over temporaries that the engine places in registers), and `binding/` (binding paths).

Handwritten code lives in `src/main/kotlin/app/reseam/patch/`. Everything under `generated/` is BoltFFI output: never edit it by hand.

## Adding an engine capability

1. Add an `#[export]` function in `crates/patcher/src/bridge/`.
2. Regenerate the bindings:

   ```bash
   cargo xtask regen patch-api   # this package only
   cargo xtask regen all         # this package, the app SDK and the browser models
   ```

3. Wrap the generated call in handwritten Kotlin. Patches call the wrapper, never `native` directly.

`api/reseam-patch-sdk.api` records the public API. If a change to it is intended, update it with `./gradlew :reseam-patch-sdk:updateKotlinAbi`; patch authors will see that change. How the bindings are generated is in [`docs/internals/boltffi.md`](../docs/internals/boltffi.md).
