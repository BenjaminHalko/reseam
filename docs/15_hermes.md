---
description: Hook the app's JavaScript functions in a Hermes bundle and call your TypeScript from the hooks.
---

# Hermes JavaScript

React Native apps often ship JavaScript as Hermes bytecode in `assets/index.android.bundle`. Reseam opens that bundle in the base APK when a patch first uses it, links your extensions into it, and writes the patched bundle stored, without ZIP compression, when the run finishes. A bundle no patch changed is left as it was.

Version 98 is supported. A missing bundle, another file format, or another bytecode version fails the patch.

You hook the app's functions the way you [change methods](8_changing_code.md), and call your extension's functions from the hooks.

## Write an extension

Put the code in its own extension module, with one entry module:

```text
apps/example/extensions/features/
  src/main/js/index.ts
```

The entry is `index.ts`, `index.tsx`, `index.js` or `index.jsx`. The build bundles it with the modules it imports by relative path and compiles it with the pinned Hermes compiler. Kotlin and JavaScript extensions need separate modules.

Export functions that take values and return values:

```typescript
export function withFeature(flags: Record<string, boolean>, name: string) {
    return { ...flags, [name]: true };
}
```

The build generates a binding for each extension from its exports. A patch module in `apps/example` gets `app.reseam.example.bindings.Features`, with one property per export.

The extension first runs when a hook first calls it. Its exports stay private; they do not add properties to the app's global object.

## Find and hook

Declare the app function as a target, like a DEX `method { }`, and hook it in `execute`:

```kotlin
import app.reseam.example.bindings.Features

val allFeatures = patch("All features") {
    execute {
        featureFlags.after {
            returnValue(Features.withFeature(result, string("search")))
        }
    }
}

private val featureFlags = function {
    name("getFeatureFlags")
    strings("FEATURE_FLAGS")
    paramCount(1)
}
```

Every constraint must match. `name` is exact and case-sensitive; `strings` matches instruction references, including property names; `paramCount` excludes `this`. Zero or several matches fail the patch. A target resolves when a patch first uses it.

| On a function | |
|---|---|
| `before { }` | at the start; falling off the end calls the function with its arguments, as assigned |
| `after { }` | after it returns; `result` is the returned value |
| `replace { }` | instead of the function; falling off the end returns `undefined` |

| | |
|---|---|
| `thisObject`, `param(i)`, `result` | the receiver, the arguments, and the returned value |
| `int(1)`, `bool(true)`, `string("x")`, `nullValue`, `undefinedValue` | constants |
| `Features.withFeature(args)` | call an export and use its result |
| `value.assign(v)` | overwrite an argument, the result, or a call's result |

Branches and returns work as for methods; branches test truthiness, `== null` and strict equality. The [reference](reference.md#hermes) lists everything.

Hooks compose in the order patches apply them: a later hook runs around the function as earlier hooks left it, and `replace` stops earlier hooks from running. Lookups still match the original app functions.

Generators, async functions, class constructors, functions using `new.target` or direct `eval`, and environment chains Reseam cannot establish fail explicitly. A hooked function cannot be called with `new` afterwards. Edited functions may lose their debug information.

## Use the app's modules

React Native bundles keep their source modules in Metro, the bundle's module system. Your extension imports them with bare specifiers:

```typescript
import UserStore from "app:stores/UserStore.tsx";
import { cloneElement } from "react";
```

One patch declares which Metro module each specifier means, for the whole run. Patches whose extensions import app modules depend on it:

```kotlin
val appModules = patch {
    execute {
        hermes.modules {
            module("react", function { name("isValidElement"); paramCount(1) }.module)
            prefix("app:") { path -> function { strings(path); paramCount(7) }.module }
        }
    }
}
```

`FunctionTarget.module` is the Metro module whose factory creates the function, or the function itself when it is a factory. A function created outside a module, or in several, fails the patch. JSX compiles to the app's `react/jsx-runtime`, so declare it when you use JSX.

Each import is read through Metro the first time your extension uses it, so an extension that runs early in the app's startup never sees a half-initialized module.

## Type checking

An app folder with a `package.json` is type checked before its extensions are bundled, as `typecheck-<app>`. `package.json` pins the type packages your extensions use as `devDependencies`, `package-lock.json` holds their integrity, and `tsconfig.json` configures the check. Only the packages' declaration files are installed, into the folder's `node_modules`.

A Kotlin extension in the same app that defines React Native modules gets declarations for them. A class with `@ReactModule(name = ...)` and `@ReactMethod` methods becomes a typed member of `NativeModules` from `react-native`, in the extension's `build/reseam/types`. Include `extensions/*/build/reseam/types` in `tsconfig.json`, and a call with a wrong method name or argument count fails the check.

## Gate on a setting

`alwaysReturn()`, `alwaysReturnNull()` and `alwaysReturn(value)` replace a function's body with a constant result, as they do for DEX methods.

[Gates](8_changing_code.md#gates) apply to Hermes targets too:

```kotlin
gate(AppSettings.allFeatures) {
    isStaff.alwaysReturn(true)
    featureFlags.after { returnValue(Features.withFeature(result, string("search"))) }
    renderRow.setArgument(0, "options.animate", false)
}
```

While the toggle is off, the unchanged function runs, so your extension never checks settings itself. `setArgument(index, path, value)` calls the function with one property of an argument replaced; the function receives copies of the objects along the path. Each toggle is read once per process through the [app runtime](5_settings.md#react-native-apps), whose React Native package your settings host adds to the app.
