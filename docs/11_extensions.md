---
description: Write Kotlin that runs inside the patched app, and call it from patches.
---

# Shipping your own code

Anything longer than a few lines is easier to write in Kotlin than to emit instruction by instruction. Put it in an *extension*: a Kotlin module in your bundle. Reseam compiles it into a DEX file and adds it to the app the first time a patch uses one of its classes.

## Write it

```text
apps/example/extensions/ads/
  src/main/kotlin/app/example/ads/AdBlocker.kt
  src/stubs/java/com/example/app/FeedItem.java
```

- `src/main/kotlin` is your code. It compiles against Android's `android.jar`.
- `src/stubs/java` holds Java stand-ins for app classes you need to compile against. Stubs are never shipped; the real classes come from the app.

Code used by several apps goes in `shared/<name>/` instead of under an app. The Kotlin that hooks it in can sit next to it, in `shared/<name>/patch/`, and app patch modules depend on that with `compileOnly(project(":shared:<name>:patch"))`.

## Export what patches call

Mark every class, method and field a patch refers to with `@Export`:

```kotlin
@file:JvmName("AdBlocker")

package app.example.ads

import app.reseam.runtime.Export

@Export
fun init(context: Context) { … }

@Export
fun onFeedLoad(items: MutableList<Any?>?) { … }
```

Top-level functions in a file with `@file:JvmName` become static methods of that class; so do `@JvmStatic` members of an `object`. Use `@JvmField` or `const val` for fields a patch reads or writes. Mark a class itself when a patch names the class, for example to make an app class implement it.

The build generates a binding for each class with exports, in the patch module of the same app (or of the same `shared/` folder): `app.example.ads.bindings.AdBlocker`. Use it in code blocks:

```kotlin
import app.example.ads.bindings.AdBlocker

appEntry { AdBlocker.init(application) }
loadFeed.before { AdBlocker.onFeedLoad(thisObject.field(feedItems)) }
```

A binding member is named after the exported member. Two overloads need different binding names: `@Export(name = "addWindow")`. A renamed or removed export breaks the patch module's build, not the patch run.

Patch modules outside the `apps/` and `shared/` layout add extensions with `reseamExtensions(project(":my-extension"))`.

> [!WARNING]
> Parameters a patch passes in come from the app's code. Declare them nullable when the app can pass `null`: Reseam compiles extensions without Kotlin's null checks, so a `null` in a non-null parameter fails only where it is used.

## The Kotlin standard library

Each bundle ships one copy of the parts of the Kotlin standard library its extensions use, moved to its own package. Apps that contain Kotlin keep their copy, and bundles patched together never share one. Two things follow:

- A class an extension implements for the app, such as the app's `kotlin.jvm.functions.Function0`, must be declared in `src/stubs/java`. Stubbed classes keep their names; every other `kotlin.*` class is the bundle's own.
- Do not pass Kotlin function types or other `kotlin.*` types between extensions of different bundles.

Coroutines and other `kotlinx` libraries are not supported.

## Dependencies

A module only needs a `build.gradle.kts` to declare dependencies:

```kotlin
dependencies {
    compileOnly(project(":shared:player-stubs"))
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
```

- `implementation`: shipped inside your extension. Android libraries (`.aar`) work too, but only their classes are used, and libraries with native code are refused.
- `compileOnly`: expected to be there already, in the app or in another module of your bundle. Use it for libraries the app already contains, including Android libraries with native code.

Every extension also compiles against the [app runtime](5_settings.md#the-app-runtime), the engine's settings store, string tables and branded components in `app.reseam.runtime`, and against your bundle's [settings modules](5_settings.md). The engine links the runtime into the app, so extensions never ship it, and a bundle that defines a class in that package fails to load.

Two extensions defining the same class differently fail the bundle when it loads. `reseam bundle list <bundle> --verbose` shows which DEX files a bundle contains.

Next: [Raw bytecode](12_bytecode.md).
