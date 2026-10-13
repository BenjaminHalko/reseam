// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/** `hide_shorts_button` → `hideShortsButton`, escaped where Kotlin reserves the word. */
internal fun kotlinName(name: String): String {
    val camel =
        name
            .split('_')
            .filter(String::isNotEmpty)
            .mapIndexed { i, part ->
                if (i == 0) part else part.replaceFirstChar(Char::uppercaseChar)
            }
            .joinToString("")
    return if (camel in KOTLIN_KEYWORDS) "`$camel`" else camel
}

internal fun kotlinString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}

internal class KotlinWriter {
    private val out = StringBuilder()
    private var indent = 0

    fun line(text: String = "") {
        if (text.isNotEmpty()) repeat(indent) { out.append("    ") }
        out.append(text).append('\n')
    }

    fun block(header: String, body: KotlinWriter.() -> Unit) {
        line("$header {")
        indent++
        body()
        indent--
        line("}")
    }

    override fun toString() = out.toString()
}

private val KOTLIN_KEYWORDS =
    setOf(
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
    )

internal val RESERVED_NAMES = setOf("id", "table")

/** Registers the string table of a module with `src/main/strings`, or returns `null`. */
internal fun Project.registerStrings(): TaskProvider<StringsTask>? {
    val sources = layout.projectDirectory.dir("src/main/strings")
    if (!sources.asFile.isDirectory) return null
    val strings = extensions.create("reseamStrings", ReseamStringsExtension::class.java)
    strings.tableId.convention(strings.objectName.map { it.substringAfterLast('.') })
    if (inBundle()) {
        strings.assetDirectory.convention(
            bundleInfo().name.zip(strings.tableId) { bundle, table ->
                "reseam/$bundle/strings/$table"
            }
        )
    }
    return tasks.register("generateStrings", StringsTask::class.java) {
        this.sources.set(sources)
        objectName.set(strings.objectName)
        tableId.set(strings.tableId)
        assetDirectory.set(strings.assetDirectory)
        json.set(layout.buildDirectory.dir("generated/reseam/strings/json"))
        device.set(layout.buildDirectory.dir("generated/reseam/strings/device"))
        host.set(layout.buildDirectory.dir("generated/reseam/strings/host"))
    }
}
