// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.patch.settings

import app.reseam.patch.FileScope
import app.reseam.patch.types.RunningBundleInfo

/**
 * A bundle's settings as the app runtime reads them: `assets/reseam/<bundle>/settings.json`, and
 * the brand's font beside it.
 */
internal class SettingsFiles(
    private val bundle: RunningBundleInfo,
    private val brand: Brand,
    contributions: List<SettingsSection>,
) {
    private val sections = contributions.filter { it.settings.isNotEmpty() }
    private val directory = "assets/reseam/${bundle.name}"

    fun write(files: FileScope) {
        files.write("$directory/$SCHEMA", schema().toByteArray(Charsets.UTF_8))
        brand.font?.let { files.writeStored("$directory/$FONT", it.file) }
    }

    private fun schema(): String {
        val pages = linkedMapOf<String, SettingsPage>()
        fun addPage(page: SettingsPage) {
            page.parent?.let(::addPage)
            val previous = pages.putIfAbsent(page.id, page)
            require(previous == null || previous == page) {
                "Conflicting settings page '${page.id}'"
            }
        }
        sections.forEach { it.page?.let(::addPage) }
        val merged =
            sections
                .groupBy { it.page?.id to it.title }
                .map { (location, contributions) ->
                    Triple(
                        location.first,
                        location.second,
                        contributions.flatMap { it.settings }.distinctBy { it.key },
                    )
                }
        return Json.build {
            obj {
                field("bundle") {
                    obj {
                        field("name", bundle.name)
                        field("version", bundle.version.ifEmpty { null })
                    }
                }
                field("engine", bundle.engine)
                field("license", bundle.license.ifEmpty { null })
                field("brand") { brand() }
                field("pages") {
                    array(pages.values.sortedBy { it.order }) { page ->
                        obj {
                            field("id", page.id)
                            field("title", page.title)
                            field("summary", page.summary)
                            field("icon") { vector(page.icon) }
                            field("parent", page.parent?.id)
                        }
                    }
                }
                field("sections") {
                    array(merged) { (page, title, settings) ->
                        obj {
                            field("page", page)
                            field("title", title)
                            field("settings") { array(settings) { setting(it) } }
                        }
                    }
                }
            }
        }
    }

    private fun Json.brand() = obj {
        field("name", brand.name)
        field("light") { colors(brand.light) }
        field("dark") { colors(brand.dark) }
        field("logo") {
            val logo = brand.logo
            if (logo == null) nothing()
            else
                obj {
                    field("light") { vector(logo.light) }
                    field("dark") { vector(logo.dark) }
                }
        }
        field("font", brand.font?.let { FONT })
        field("typography") {
            val type = brand.typography
            obj {
                field("headlineSmall") { style(type.headlineSmall) }
                field("titleLarge") { style(type.titleLarge) }
                field("titleMedium") { style(type.titleMedium) }
                field("bodyLarge") { style(type.bodyLarge) }
                field("bodyMedium") { style(type.bodyMedium) }
                field("labelLarge") { style(type.labelLarge) }
                field("labelMedium") { style(type.labelMedium) }
            }
        }
    }

    private fun Json.colors(scheme: ColorScheme) = obj {
        field("primary", scheme.primary)
        field("onPrimary", scheme.onPrimary)
        field("primaryContainer", scheme.primaryContainer)
        field("onPrimaryContainer", scheme.onPrimaryContainer)
        field("secondaryContainer", scheme.secondaryContainer)
        field("onSecondaryContainer", scheme.onSecondaryContainer)
        field("surface", scheme.surface)
        field("surfaceContainerLow", scheme.surfaceContainerLow)
        field("surfaceContainer", scheme.surfaceContainer)
        field("surfaceContainerHigh", scheme.surfaceContainerHigh)
        field("surfaceContainerHighest", scheme.surfaceContainerHighest)
        field("onSurface", scheme.onSurface)
        field("onSurfaceVariant", scheme.onSurfaceVariant)
        field("outline", scheme.outline)
        field("outlineVariant", scheme.outlineVariant)
        field("error", scheme.error)
        field("onError", scheme.onError)
        field("errorContainer", scheme.errorContainer)
        field("onErrorContainer", scheme.onErrorContainer)
        field("scrim", scheme.scrim)
    }

    private fun Json.style(style: TextStyle) = obj {
        field("size", style.size)
        field("lineHeight", style.lineHeight)
        field("weight", style.weight)
        field("tracking", style.tracking)
    }

    private fun Json.vector(vector: Vector?) {
        if (vector == null) return nothing()
        obj {
            field("width", vector.width)
            field("height", vector.height)
            field("paths") {
                array(vector.paths) { path ->
                    obj {
                        field("data", path.data)
                        field("fill") {
                            val fill = path.fill
                            if (fill == null) nothing()
                            else
                                obj {
                                    field("paint") { paint(fill.paint) }
                                    field("evenOdd", fill.evenOdd)
                                }
                        }
                        field("stroke") {
                            val stroke = path.stroke
                            if (stroke == null) nothing()
                            else
                                obj {
                                    field("paint") { paint(stroke.paint) }
                                    field("width", stroke.width)
                                    field("cap", stroke.cap.name.lowercase())
                                    field("join", stroke.join.name.lowercase())
                                }
                        }
                    }
                }
            }
        }
    }

    /** `null` is the color the screen draws the vector in. */
    private fun Json.paint(paint: Paint) =
        when (paint) {
            Paint.Current -> nothing()
            is Paint.Solid -> value(paint.color.toString())
        }

    private fun Json.setting(setting: Setting<*>) = obj {
        field(
            "type",
            when (setting) {
                is ToggleSetting -> "toggle"
                is TextSetting -> "text"
                is FolderSetting -> "folder"
                is ChoiceSetting -> "choice"
            },
        )
        field("key", setting.key)
        field("title", setting.title)
        field("summary", setting.summary)
        field("icon") { vector(setting.icon) }
        when (setting) {
            is ToggleSetting -> field("default", setting.default)
            is TextSetting -> {
                field("default", setting.default)
                field("multiline", setting.multiline)
            }
            is FolderSetting -> field("default", setting.default)
            is ChoiceSetting -> {
                field("default", setting.default)
                field("choices") {
                    array(setting.choices) { choice ->
                        obj {
                            field("value", choice.value)
                            field("title", choice.title)
                        }
                    }
                }
            }
        }
    }

    private fun Json.field(name: String, color: Color) = field(name, color.toString())

    private companion object {
        const val SCHEMA = "settings.json"
        const val FONT = "font.ttf"
    }
}

internal class Json private constructor() {
    private val out = StringBuilder()
    private var first = true

    fun obj(block: Json.() -> Unit) {
        out.append('{')
        first = true
        block()
        out.append('}')
        first = false
    }

    fun <T> array(items: Iterable<T>, item: Json.(T) -> Unit) {
        out.append('[')
        items.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            item(value)
        }
        out.append(']')
        first = false
    }

    fun field(name: String, value: Json.() -> Unit) {
        if (!first) out.append(',')
        string(name)
        out.append(':')
        value()
        first = false
    }

    fun field(name: String, value: String?) = field(name) { value(value) }

    fun field(name: String, value: Number) = field(name) { out.append(value) }

    fun field(name: String, value: Boolean) = field(name) { out.append(value) }

    fun value(value: String?) {
        if (value == null) nothing() else string(value)
    }

    fun nothing() {
        out.append("null")
    }

    private fun string(value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (ch.code < 0x20) out.append("\\u" + ch.code.toString(16).padStart(4, '0'))
                    else out.append(ch)
            }
        }
        out.append('"')
    }

    companion object {
        fun build(block: Json.() -> Unit): String = Json().apply(block).out.toString()
    }
}
