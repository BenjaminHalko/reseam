---
description: Write a patch that removes a prompt from an app, build it, and test it.
---

# Your first patch

This page writes one patch from scratch. It removes a "rate this app" prompt from an imaginary app, `com.example.app`.

## 1. Find the code

Open the APK in a decompiler such as [jadx](https://github.com/skylot/jadx), and find the method that shows the prompt.

Most apps are obfuscated: their classes and methods get short, meaningless names like `a.b.c`, and the names change with every release. So don't write down the method's name. Write down what is likely to stay the same: the text it uses, what it returns, and what it calls.

Say the method loads the string `"rate_prompt_shown"` and returns nothing. That's enough to find it again in the next version.

## 2. Describe the method

Add a file under `apps/example/patch/src/main/kotlin/app/example/patches/`:

```kotlin
package app.example.patches

import app.reseam.patch.Type
import app.reseam.patch.method

val showRatePrompt = method("showRatePrompt") {
    strings("rate_prompt_shown")
    returns(Type.Void)
}
```

This is a *target*: a description of the method, which Reseam searches the app for when a patch uses it. `"showRatePrompt"` is a label for error messages. The app's real name for the method doesn't matter.

## 3. Write the patch

In the same file:

```kotlin
import app.reseam.patch.before
import app.reseam.patch.patch

val hideRatePrompt = patch("Hide rate prompt") {
    description("Never asks you to rate the app.")
    compatibleWith("com.example.app")

    execute {
        showRatePrompt.before {
            returnVoid()
        }
    }
}
```

- `patch("Hide rate prompt")` is the name users see.
- `compatibleWith` says which app the patch is for. It is selected by default when someone patches that app.
- `execute` is the change. Here, it adds `return` at the start of the method, so the prompt never shows.

The patch has to be a public, top-level `val`. That is how Reseam finds it.

Hooks such as `before` are extension functions, so each needs its import. Your IDE adds them for you.

## 4. Build and test

```bash
./gradlew bundle
reseam patch app.apk \
  --bundle build/reseam/my-patches.reseam \
  --trust <public key> \
  --output patched.apk
adb install patched.apk
```

The log reports each patch as it runs, and ends with a summary:

```text
INFO patch{patch=my-patches/app.example.patches.hideRatePrompt}: patch applied
INFO patch run finished applied=1 dependencies=0 skipped=0 unmountable=0 failed=0
```

Then open the app and check that the prompt is gone. A patch that applied changed the code its target matched. Only the app shows you whether that was the right code.

## 5. When the target doesn't match

If no method fits the description, the patch fails, nothing is written, and the log says why:

```text
ERROR patch failed patch=my-patches/app.example.patches.hideRatePrompt reason="… No method matched 'showRatePrompt'. Searched 3 candidate(s).
Near misses: Lcom/example/a/b;->c()Z [missed: return type mismatch]"
```

The near miss is a method that loads the string but returns a boolean (`Z`), so `returns(Type.Void)` was wrong. If several methods fit instead, the patch fails and lists them. Add a constraint that tells them apart.

[Finding code](5_finding_code.md) explains targets in detail, and [Testing and debugging](9_testing.md) covers reading these reports.

Next: [How patching works](3_how_patching_works.md).
