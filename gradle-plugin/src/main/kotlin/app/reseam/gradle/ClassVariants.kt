// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.gradle

import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet

/** Class directories one module hands another, outside Java's own variants. */
internal enum class ClassVariant(val usage: String, val dependencies: String) {
    /** An extension's classes, from which a patch module generates its `@Export` bindings. */
    EXPORTS("reseam-export-classes", "reseamExtensions"),

    /** A settings module's generated extension-side classes, which extensions compile against. */
    SETTINGS("reseam-settings-classes", "reseamSettings"),
}

internal fun Project.publish(variant: ClassVariant, sourceSet: SourceSet) {
    configurations.consumable(variant.usage) {
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, variant.usage))
        outgoing.artifacts(provider { sourceSet.output.classesDirs.files }) {
            builtBy(sourceSet.output)
        }
    }
}

/**
 * The classes of the modules added to [ClassVariant.dependencies], such as
 * `reseamSettings(project(":settings"))`.
 */
internal fun Project.resolve(variant: ClassVariant): FileCollection {
    val scope = configurations.dependencyScope(variant.dependencies)
    return configurations
        .resolvable("${variant.dependencies}Classpath") {
            extendsFrom(scope.get())
            attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage::class.java, variant.usage),
            )
        }
        .get()
        .incoming
        .files
}
