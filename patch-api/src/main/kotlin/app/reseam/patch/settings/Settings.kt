// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("unused")

package app.reseam.patch.settings

import app.reseam.patch.ActiveRuntime
import app.reseam.patch.CompatiblePackage
import app.reseam.patch.PatchDeclaration
import app.reseam.patch.PatchRuntime
import app.reseam.patch.ReseamPatch
import app.reseam.patch.appEntry
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A user-facing setting stored in the patched app and shown on the bundle's settings screen. Its
 * [key] is unique within the bundle; other bundles keep their own settings.
 */
sealed class Setting<T>(
    val key: String,
    val title: String,
    val summary: String?,
    val default: T,
    val icon: Vector?,
) {
    override fun toString() = key
}

class ToggleSetting(
    key: String,
    title: String,
    summary: String? = null,
    default: Boolean,
    icon: Vector? = null,
) : Setting<Boolean>(key, title, summary, default, icon)

/** [multiline] lets the value hold one entry per line. */
class TextSetting(
    key: String,
    title: String,
    summary: String? = null,
    default: String,
    val multiline: Boolean = false,
    icon: Vector? = null,
) : Setting<String>(key, title, summary, default, icon)

class FolderSetting(
    key: String,
    title: String,
    summary: String? = null,
    default: String,
    icon: Vector? = null,
) : Setting<String>(key, title, summary, default, icon)

class ChoiceSetting(
    key: String,
    title: String,
    summary: String? = null,
    default: String,
    val choices: List<Choice>,
    icon: Vector? = null,
) : Setting<String>(key, title, summary, default, icon) {
    init {
        require(choices.any { it.value == default }) {
            "Choice setting '$key' defaults to '$default', which is not a choice"
        }
    }

    /** The same stored setting with other choices, for a patch that extends it from its options. */
    fun copy(default: String = this.default, choices: List<Choice> = this.choices) =
        ChoiceSetting(key, title, summary, default, choices, icon)
}

data class Choice(val value: String, val title: String)

/**
 * A reusable destination, shown as a row on its parent page. Parents allow arbitrary nesting; lower
 * orders appear first.
 */
data class SettingsPage(
    val id: String,
    val title: String,
    val parent: SettingsPage? = null,
    val order: Int = 0,
    val summary: String? = null,
    val icon: Vector? = null,
) {
    init {
        require(id.isNotBlank()) { "A settings page needs a non-blank id" }
        require(title.isNotBlank()) { "A settings page needs a non-blank title" }
        var ancestor = parent
        while (ancestor != null) {
            require(ancestor.id != id) { "Settings page '$id' cannot be its own ancestor" }
            ancestor = ancestor.parent
        }
    }
}

data class SettingsSection(
    val title: String,
    val settings: List<Setting<*>>,
    val page: SettingsPage? = null,
)

/** A section on the root settings screen. */
fun section(title: String, vararg settings: Setting<*>) = SettingsSection(title, settings.toList())

/** Contributes settings to a page shared by any number of patches. */
fun section(page: SettingsPage, title: String, vararg settings: Setting<*>) =
    SettingsSection(title, settings.toList(), page)

/**
 * Declares a setting as a property: `val hideAds by toggle("Hide ads", default = true)`. The key
 * derives from the owner and property names unless given.
 */
fun toggle(
    title: String,
    summary: String? = null,
    default: Boolean,
    key: String? = null,
    icon: Vector? = null,
) = SettingDelegate(key) { ToggleSetting(it, title, summary, default, icon) }

fun text(
    title: String,
    summary: String? = null,
    default: String,
    multiline: Boolean = false,
    key: String? = null,
    icon: Vector? = null,
) = SettingDelegate(key) { TextSetting(it, title, summary, default, multiline, icon) }

fun folder(
    title: String,
    summary: String? = null,
    default: String,
    key: String? = null,
    icon: Vector? = null,
) = SettingDelegate(key) { FolderSetting(it, title, summary, default, icon) }

fun choice(
    title: String,
    summary: String? = null,
    default: String,
    choices: List<Choice>,
    key: String? = null,
    icon: Vector? = null,
) = SettingDelegate(key) { ChoiceSetting(it, title, summary, default, choices, icon) }

class SettingDelegate<S : Setting<*>>
internal constructor(
    private val key: String?,
    private val create: (key: String) -> S,
) : PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, S>> {
    override fun provideDelegate(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>,
    ): ReadOnlyProperty<Any?, S> {
        val owner = thisRef?.let { it::class.simpleName }?.let { snakeCase(it) + "." }.orEmpty()
        val setting = create(key ?: (owner + snakeCase(property.name)))
        return ReadOnlyProperty { _, _ -> setting }
    }
}

internal fun snakeCase(name: String): String = buildString {
    for ((i, ch) in name.withIndex()) {
        if (
            ch.isUpperCase() &&
                i > 0 &&
                (name[i - 1].isLowerCase() || name.getOrNull(i + 1)?.isLowerCase() == true)
        )
            append('_')
        append(ch.lowercaseChar())
    }
}

/**
 * The internal patch that gives one app the running bundle's settings screen. Patches register
 * their sections with it; after they have all run it writes the bundle's settings into the app,
 * links the app runtime, and runs the app-specific install.
 */
class SettingsHost
internal constructor(
    val appId: String,
    override val compatibleWith: List<CompatiblePackage>,
    override val dependencies: List<ReseamPatch>,
    private val ownSections: List<SettingsSection>,
    private val brand: Brand,
    private val install: PatchRuntime.() -> Unit,
) : ReseamPatch {
    override val name: String? = null
    override val description = "Settings for $appId"

    fun register(patch: ReseamPatch, sections: List<SettingsSection>) {
        if (sections.isEmpty()) return
        ActiveRuntime.current.afterSuccess {
            ActiveRuntime.current.run.register(this, patch, sections)
        }
    }

    /** The host runs before dependents, so its own sections lead the screen. */
    override fun execute(ctx: PatchRuntime) {
        ctx.run.clear(this)
        register(this, ownSections)
    }

    override fun afterDependents(ctx: PatchRuntime) {
        try {
            ctx.run.claimSettings(ctx.bundle.name, this)
            SettingsFiles(ctx.bundle, brand, ctx.run.sections(this)).write(ctx.files)
            ctx.install()
            // appEntry code runs newest first, so this init precedes every other entry hook.
            appEntry { call(Reseam.init, application) }
        } finally {
            ctx.run.clear(this)
        }
    }

    override fun toString() = "$appId settings"
}

class SettingsHostBuilder internal constructor() : PatchDeclaration() {
    private var installBlock: PatchRuntime.() -> Unit = {}
    private val sections = mutableListOf<SettingsSection>()
    private var brand = Brand.DEFAULT

    /** Sections the host owns, for settings that belong to no single feature patch. */
    fun settings(vararg sections: SettingsSection) {
        this.sections += sections
    }

    /** How the settings screen and the components the bundle's extensions build look. */
    fun brand(brand: Brand) {
        this.brand = brand
    }

    /**
     * Wires the settings screen into the app, typically a row in the app's own settings that calls
     * [openSettings]; runs once every patch has registered its sections.
     */
    fun install(block: PatchRuntime.() -> Unit) {
        installBlock = block
    }

    internal fun build(appId: String) =
        SettingsHost(
            appId,
            compatibility.toList(),
            dependencies.toList(),
            sections.toList(),
            brand,
            installBlock,
        )
}

fun settingsHost(appId: String, block: SettingsHostBuilder.() -> Unit): SettingsHost =
    SettingsHostBuilder().apply(block).build(appId)
