// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.patch.settings

import app.reseam.patch.CodeScope
import app.reseam.patch.FunctionTarget
import app.reseam.patch.JsCodeScope
import app.reseam.patch.JsOtherwise
import app.reseam.patch.MethodTarget
import app.reseam.patch.Otherwise
import app.reseam.patch.PointTarget
import app.reseam.patch.Type
import app.reseam.patch.after
import app.reseam.patch.before
import app.reseam.patch.skipWhen

/** Runs `block` in the app when the running bundle's toggle is on. */
fun CodeScope.whenEnabled(setting: ToggleSetting, block: CodeScope.() -> Unit): Otherwise =
    whenTrue(valueOf(setting), block)

/** Runs `block` in the app's JavaScript when the running bundle's toggle is on. */
fun JsCodeScope.whenEnabled(setting: ToggleSetting, block: JsCodeScope.() -> Unit): JsOtherwise =
    whenTrue(valueOf(setting), block)

/**
 * Applies the hooks written in [block] only while the running bundle's [setting] is on. Hermes
 * hooks read it once per process through the app runtime, which needs the app's settings host to
 * call [addReactPackage] with the app's React Native packages.
 *
 * ```kotlin
 * gate(AppSettings.hideAds) {
 *     loadAds.alwaysReturnNull()
 *     isFeatureEnabled.wrap(Features.isFeatureEnabled)
 * }
 * ```
 */
fun gate(setting: ToggleSetting, block: GateScope.() -> Unit) = GateScope(setting).block()

/** Hooks that run only while a [gate]'s setting is on. Each mirrors the ungated hook. */
class GateScope internal constructor(val setting: ToggleSetting) {
    fun MethodTarget.before(block: CodeScope.() -> Unit) = beforeOn(setting, block)

    fun MethodTarget.after(block: CodeScope.() -> Unit) = afterOn(setting, block)

    fun PointTarget.before(block: CodeScope.() -> Unit) = beforeOn(setting, block)

    fun PointTarget.after(block: CodeScope.() -> Unit) = afterOn(setting, block)

    /** Skips the call at the point; the call's result must be unused. */
    fun PointTarget.skip() = skipWhen { valueOf(setting) }

    fun MethodTarget.alwaysReturn() = returnOn(setting, Type.Void) { returnVoid() }

    fun MethodTarget.alwaysReturnNull() {
        require(returnType.startsWith("L") || returnType.startsWith("[")) {
            "the ${setting.key} gate returns null, but $descriptor returns $returnType"
        }
        beforeOn(setting) { returnNull() }
    }

    fun MethodTarget.alwaysReturn(value: Boolean) =
        returnOn(setting, Type.Boolean) { if (value) returnTrue() else returnFalse() }

    fun MethodTarget.alwaysReturn(value: Int) =
        returnOn(setting, Type.Int) { returnValue(int(value)) }

    fun MethodTarget.alwaysReturn(value: Long) =
        returnOn(setting, Type.Long) { returnValue(long(value)) }

    fun MethodTarget.alwaysReturn(value: String) =
        returnOn(setting, Type.String) { returnValue(string(value)) }

    fun FunctionTarget.before(block: JsCodeScope.() -> Unit) = beforeOn(setting, block)

    fun FunctionTarget.after(block: JsCodeScope.() -> Unit) = afterOn(setting, block)

    /** Runs [block] instead of the function; falling off its end returns `undefined`. */
    fun FunctionTarget.replace(block: JsCodeScope.() -> Unit) =
        beforeOn(setting) {
            block()
            returnUndefined()
        }

    fun FunctionTarget.alwaysReturn() = replace { returnUndefined() }

    fun FunctionTarget.alwaysReturnNull() = replace { returnNull() }

    fun FunctionTarget.alwaysReturn(value: Boolean) = replace { returnValue(bool(value)) }

    fun FunctionTarget.alwaysReturn(value: Int) = replace { returnValue(int(value)) }

    fun FunctionTarget.alwaysReturn(value: String) = replace { returnValue(string(value)) }

    /**
     * Calls the function with a copy of argument [index] whose property at [path] is [value].
     * [path] names nested properties with dots, such as `options.compact`; each object along it is
     * shallow-copied, so the caller's object is not changed.
     */
    fun FunctionTarget.setArgument(index: Int, path: String, value: Boolean) {
        require(index >= 0) { "setArgument needs a nonnegative argument index, got $index" }
        require(path.split('.').none(String::isEmpty)) {
            "setArgument needs property names separated by dots, got '$path'"
        }
        beforeOn(setting) {
            param(index).assign(AppRuntimeJs.withProperty(param(index), string(path), bool(value)))
        }
    }
}

private fun MethodTarget.beforeOn(setting: ToggleSetting, block: CodeScope.() -> Unit) = before {
    whenEnabled(setting, block)
}

private fun MethodTarget.afterOn(setting: ToggleSetting, block: CodeScope.() -> Unit) = after {
    whenEnabled(setting, block)
}

private fun PointTarget.beforeOn(setting: ToggleSetting, block: CodeScope.() -> Unit) = before {
    whenEnabled(setting, block)
}

private fun PointTarget.afterOn(setting: ToggleSetting, block: CodeScope.() -> Unit) = after {
    whenEnabled(setting, block)
}

private fun MethodTarget.returnOn(
    setting: ToggleSetting,
    type: String,
    block: CodeScope.() -> Unit,
) {
    require(returnType == type) {
        "the ${setting.key} gate returns $type, but $descriptor returns $returnType"
    }
    beforeOn(setting, block)
}

private fun FunctionTarget.beforeOn(setting: ToggleSetting, block: JsCodeScope.() -> Unit) =
    before {
        whenEnabled(setting, block)
    }

private fun FunctionTarget.afterOn(setting: ToggleSetting, block: JsCodeScope.() -> Unit) = after {
    whenEnabled(setting, block)
}
