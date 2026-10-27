---
title: "API: Hermes"
description: Finding and hooking JavaScript functions in a React Native app's Hermes bundle.
---

# API: Hermes

For React Native apps that ship their JavaScript as Hermes bytecode. The guide page is [Hermes JavaScript](14_hermes.md). Everything here is in `app.reseam.patch`.

## The bundle

### `HermesScope`

The `hermes` of a running patch: the base APK's `assets/index.android.bundle`. It opens the first time a patch uses it, and is written back when the run finishes. A missing bundle, a file that isn't Hermes bytecode, or an unsupported bytecode version fails the patch. Version 98 is supported.

| Member | Description |
|---|---|
| `version: Int` | Opens the bundle and returns its bytecode version. |
| `modules(block: AppModules.() -> Unit)` | Declares which app modules your extensions' imports mean, for the rest of the run. Patches whose extensions import app modules must depend on the patch that calls this. |

### `AppModules`

The receiver of `hermes.modules { }`. An extension imports app modules with bare specifiers, such as `react` or `app:screens/Home.tsx`. Each specifier resolves to the Metro module declared here.

| Member | Description |
|---|---|
| `module(specifier: String, module: MetroModule)` | The module this exact specifier means. |
| `prefix(prefix: String, module: (path: String) -> MetroModule)` | Resolves every specifier that starts with `prefix` and has no `module` declaration. The function gets the rest of the specifier. |

## Finding functions

### `function`

`function(debugName: String? = null, block: HermesFunctionQuery.() -> Unit): FunctionTarget` is the one app function matching every constraint. No match, or several, fails the patch. Your extensions' functions are never matched.

### `HermesFunctionQuery`

| Member | Matches functions that |
|---|---|
| `name(value: String)` | have exactly this name, case included. |
| `strings(vararg values: String)` | reference each of these strings in an instruction, property names included. |
| `paramCount(count: Int)` | declare this many parameters, not counting `this`. |

### `FunctionTarget`

A function in the app's bundle. Hooks attach to it; see [Hooks](#hooks).

| Member | Description |
|---|---|
| `module: MetroModule` | The Metro module whose factory creates this function, or the function itself when it is a factory. A function created outside a module, or in several, fails the patch. |

### `MetroModule`

One of the app's source modules, as Metro, React Native's bundler, registers it. You get one from `FunctionTarget.module` and pass it to `hermes.modules { }`.

## Hooks

| Function | Description |
|---|---|
| `FunctionTarget.before(block: JsCodeScope.() -> Unit)` | Runs at the start. Falling off the end calls the function with its arguments, as the block left them. `returnValue` returns early instead. |
| `FunctionTarget.after(block: JsCodeScope.() -> Unit)` | Runs after the function returns. `result` is its result, which falling off the end returns. |
| `FunctionTarget.replace(block: JsCodeScope.() -> Unit)` | Runs instead of the function. Falling off the end returns `undefined`. Hooks added before it no longer run. |
| `FunctionTarget.alwaysReturn()` | Replaces the body with `return undefined`. |
| `FunctionTarget.alwaysReturn(value: Boolean)`, `(value: Int)`, `(value: String)` | Replaces the body with `return value`. |
| `FunctionTarget.alwaysReturnNull()` | Replaces the body with `return null`. |

Hooks compose in the order patches add them: a later hook runs around the function as earlier hooks left it. Targets still match the original app functions, so hooking a function doesn't change what a later query finds.

Some functions can't be hooked, and fail the patch: generators, async functions, class constructors, functions that use `new.target` or direct `eval`, and functions whose surrounding scopes Reseam can't work out. A hooked function can't be called with `new` afterwards, and may lose its debug information.

### `JsCodeScope`

The receiver of a Hermes hook. Like a [`CodeScope`](reference_code.md#codescope), each call adds code to the app. Branches test as JavaScript does: truthiness, `== null`, and strict equality.

| Member | Description |
|---|---|
| `thisObject: JsValue` | The `this` the function was called with. |
| `param(index: Int): JsValue` | An argument, as assigned so far. `undefined` past the last one. |
| `result: JsValue` | The returned value, in `after { }`. |
| `int(value: Int): JsValue`, `bool(value: Boolean): JsValue`, `string(value: String): JsValue` | Constants. |
| `nullValue: JsValue`, `undefinedValue: JsValue` | `null` and `undefined`. |
| `call(export: JsExport, vararg args: JsValue): JsValue` | Calls an extension's export, with `this` undefined. |
| `JsExport.invoke(vararg args: JsValue): JsValue` | The same as `call`, written as a call: `Features.withFeature(result, string("search"))`. |
| `whenTrue(value, block)`, `whenFalse(value, block)` | Runs the block when `value` is truthy, or falsy. |
| `whenNull(value, block)`, `whenNotNull(value, block)` | Runs the block when `value` is `null` or `undefined`, or neither. |
| `whenEqual(left, right, block)`, `whenNotEqual(left, right, block)` | Runs the block when `left === right`, or `!==`. |
| `returnValue(value: JsValue)` | Returns `value`. |
| `returnUndefined()`, `returnNull()`, `returnTrue()`, `returnFalse()` | Returns that constant. |

Each `when…` returns a [`JsOtherwise`](#jsotherwise). Settings add [`whenEnabled`](reference_settings.md#whenenabled) and [`valueOf`](reference_settings.md#reading-settings-in-code-blocks).

### `JsValue`

A value in a Hermes code block. `assign(value: JsValue)` overwrites it in place: an argument, the result, or an earlier call's result. Constants can't be assigned.

### `JsOtherwise`

What a `when…` function returns. `otherwise(block: JsCodeScope.() -> Unit)` adds the other branch.

## Extensions

### `ExtJsModule`

A JavaScript extension of your bundle. The Gradle plugin generates one per extension, from its entry module's exports, as `app.reseam.<folder>.bindings.<Extension>`. For `apps/example/extensions/features`, that is `app.reseam.example.bindings.Features`.

| Member | Description |
|---|---|
| `ExtJsModule(name: String)` | The extension, by its bundle name, such as `example-features`. |
| `name: String` | Its name. |
| `export(name: String? = null)` | Declares an exported function, named after the property unless `name` is given. |

The extension's code first runs when a hook first calls one of its exports. Its exports stay private, and add nothing to the app's global object.

### `JsExport`

A function an extension exports.

| Member | Description |
|---|---|
| `module: ExtJsModule` | Its extension. |
| `name: String` | Its exported name. |
