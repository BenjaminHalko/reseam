// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.patch

import app.reseam.patch.native.hermesAlwaysReturn
import app.reseam.patch.native.hermesFindFunction
import app.reseam.patch.native.hermesVersion
import app.reseam.patch.native.hermesWrap
import app.reseam.patch.types.HermesArgument
import app.reseam.patch.types.HermesConstant
import app.reseam.patch.types.HermesModule

/**
 * The base APK's Hermes bytecode in `assets/index.android.bundle`, opened on first use and written
 * when the patch run finishes. Missing bundles, non-Hermes files and unsupported bytecode versions
 * fail the patch. Version 98 is currently supported.
 *
 * Functions and extensions are linked at patch time. Extension initialization runs once before the
 * app's global code, with exports held in a private lexical environment.
 */
class HermesScope internal constructor() {
    /** Opens the bundle and returns its bytecode version. */
    val version: Int
        get() = hermesVersion().toInt()
}

/**
 * The one app function in the Hermes bundle matching every constraint in [block]. Missing and
 * ambiguous matches fail with the query and match count. Extension functions are excluded.
 *
 * ```kotlin
 * private val isFeatureEnabled = function {
 *     name("isFeatureEnabled")
 *     strings("FEATURE_FLAGS")
 *     paramCount(1)
 * }
 * ```
 */
fun function(debugName: String? = null, block: HermesFunctionQuery.() -> Unit): FunctionTarget =
    FunctionTarget(debugName, HermesQuery().apply(block))

/** Constraints combined with AND when finding a Hermes function. */
interface HermesFunctionQuery {
    /** Matches the function's bytecode name exactly, including case. */
    fun name(value: String)

    /** Requires every string to be referenced by an instruction, including property names. */
    fun strings(vararg values: String)

    /** Matches the declared JavaScript parameter count, excluding `this`. Must be nonnegative. */
    fun paramCount(count: Int)
}

internal class HermesQuery : HermesFunctionQuery {
    var name: String? = null
    val strings = mutableListOf<String>()
    var parameters: UInt? = null

    override fun name(value: String) {
        name = value
    }

    override fun strings(vararg values: String) {
        strings += values
    }

    override fun paramCount(count: Int) {
        require(count >= 0) { "parameter count must be nonnegative" }
        parameters = count.toUInt()
    }

    override fun toString() = buildList {
        name?.let { add("name $it") }
        if (strings.isNotEmpty()) add("strings ${strings.joinToString()}")
        parameters?.let { add("$it parameters") }
    }
        .joinToString(", ")
}

/** A function in the app's Hermes bundle. */
class FunctionTarget internal constructor(debugName: String?, private val query: HermesQuery) :
    Target<UInt>(debugName) {
    override val label: String
        get() = debugName ?: query.name ?: "function with $query"

    override fun resolve(runtime: PatchRuntime): Resolution<UInt> =
        Resolution(
            hermesFindFunction(query.name, query.strings, query.parameters),
            wrapped(label, query.toString()),
        )

    /** Like [wrap], passing [bound] to [export] ahead of `original`. */
    internal fun wrapBound(export: JsExport, bound: List<HermesArgument>) =
        hermesWrap(resolved, export.module.source, export.name, bound)

    internal fun returnConstant(value: HermesConstant) = hermesAlwaysReturn(resolved, value)

    /**
     * The Metro module that defines this function, for passing to [wrap]. React Native bundles
     * register each source module with Metro; its exports hold the module's functions, and a store
     * module's `default` export is the store. The module is the one whose factory creates this
     * function, found when the wrap is made. A function created outside a module, or in several,
     * fails the patch.
     */
    val module: MetroModule
        get() = MetroModule(this)
}

/**
 * A Metro module of the app's bundle, named by a function it defines. See [FunctionTarget.module].
 */
class MetroModule internal constructor(private val function: FunctionTarget) {
    internal val argument: HermesArgument
        get() = HermesArgument.Module(function.resolved)
}

/**
 * Calls [export] as `export(original, ...arguments)` with the function's receiver, returning the
 * export's result. `original` invokes the previous wrap, or the unchanged body for the first wrap,
 * bound to that receiver and closed over the original outer environment. The export may call it
 * with different arguments, skip it or transform its result; exceptions propagate normally.
 *
 * Wraps compose in application order (patch execution order), including exports from different
 * modules. The last wrap runs outermost; the innermost `original` calls the unchanged app body.
 * Lookups continue to match original app functions.
 *
 * The extension links once, when first used. Missing modules and undeclared or non-callable exports
 * fail the patch. Generators, async functions, class constructors, functions using `new.target` or
 * direct `eval`, and unprovable environment chains are refused. Ordinary wrapped functions cannot
 * be called with `new` afterwards. Debug data for edited functions may be dropped.
 */
fun FunctionTarget.wrap(export: JsExport) = wrapBound(export, emptyList())

/**
 * Like [wrap], passing the exports of each of [modules] to [export] ahead of `original`:
 * `export(...modules, original, ...arguments)`. Each module is read through Metro's `__r` whenever
 * the function runs, which initializes it first if the app has not loaded it yet.
 *
 * ```kotlin
 * handlePressSend.wrap(Preview.handlePressSend, receiveMessage.module, getDraft.module)
 * ```
 */
fun FunctionTarget.wrap(export: JsExport, vararg modules: MetroModule) =
    wrapBound(export, modules.map { it.argument })

/**
 * Replaces the function's body with `return undefined`. Earlier wraps of the function no longer
 * run; later wraps receive the constant body as `original`.
 */
fun FunctionTarget.alwaysReturn() = returnConstant(HermesConstant.Undefined)

/** Replaces the function's body with `return null`. */
fun FunctionTarget.alwaysReturnNull() = returnConstant(HermesConstant.Null)

/** Replaces the function's body with `return value`. */
fun FunctionTarget.alwaysReturn(value: Boolean) = returnConstant(HermesConstant.Bool(value))

/** Replaces the function's body with `return value`. */
fun FunctionTarget.alwaysReturn(value: Int) = returnConstant(HermesConstant.Int(value))

/** Replaces the function's body with `return value`. */
fun FunctionTarget.alwaysReturn(value: String) = returnConstant(HermesConstant.Text(value))

/**
 * A JavaScript extension's artifact name, matching its workspace module: for
 * `apps/example/extensions/features`, use `example-features`. Names contain only ASCII letters,
 * digits, hyphens and underscores; others fail when first used. Sources live in `src/main/js` and
 * assign callable properties to `exports`. Keep export assignments unconditional.
 *
 * ```kotlin
 * object Features : ExtJsModule("example-features") {
 *     val isFeatureEnabled by export()
 * }
 * ```
 */
open class ExtJsModule(val name: String) {
    internal open val source: HermesModule
        get() = HermesModule.Bundle(name)

    /**
     * Declares a callable property of the extension's exports object, named after the property, or
     * [name]. Validated on use.
     */
    fun export(name: String? = null): MemberDelegate<JsExport> =
        MemberDelegate(name) { JsExport(this, it) }
}

/** A declared callable JavaScript export; it does not load or execute the module by itself. */
class JsExport internal constructor(val module: ExtJsModule, val name: String)
