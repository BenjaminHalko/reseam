// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.gradle

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/** `generatePatchesJson` and `stageRelease`, which publish a packed bundle. */
internal fun Project.registerRelease(
    bundle: TaskProvider<Exec>,
    bundleFile: Provider<RegularFile>,
    bundleName: Provider<String>,
    version: Provider<String>,
    reseamBin: Provider<File>,
) {
    val env = { name: String -> providers.environmentVariable(name) }
    val releaseTag = providers.gradleProperty("releaseTag")
    val patchesJson =
        tasks.register("generatePatchesJson", Exec::class.java) {
            group = "distribution"
            description = "Writes the patches.json release index for the bundle."
            dependsOn(bundle)
            val url =
                env("RESEAM_BUNDLE_URL")
                    .orElse(
                        releaseTag.zip(bundleName) { tag, name ->
                            "https://api.reseam.app/patches/$tag/$name.reseam"
                        }
                    )
            val outFile =
                env("RESEAM_PATCHES_JSON_OUT")
                    .map(project::file)
                    .orElse(
                        layout.buildDirectory.file("reseam/patches.json").map {
                            it.asFile
                        }
                    )
            val homepage = env("RESEAM_HOMEPAGE").orElse("https://reseam.app")
            val description = env("RESEAM_RELEASE_DESCRIPTION").orElse("")
            val descriptionFile = env("RESEAM_RELEASE_DESCRIPTION_FILE").map(project::file)
            val createdAt = env("RESEAM_RELEASE_CREATED_AT").orElse("")
            val prerelease =
                env("RESEAM_RELEASE_PRERELEASE")
                    .map { it.equals("true", true) || it == "1" }
                    .orElse(false)
            inputs.file(bundleFile)
            inputs.file(reseamBin)
            inputs.property("url", url.orElse(""))
            inputs.property("homepage", homepage)
            inputs.property("description", description)
            inputs.file(descriptionFile).optional()
            inputs.property("createdAt", createdAt)
            inputs.property("prerelease", prerelease)
            outputs.upToDateWhen { false }
            outputs.file(outFile)
            doFirst {
                if (!version.isPresent) {
                    throw GradleException("RESEAM_RELEASE_VERSION or -PreleaseTag is required")
                }
                val args =
                    mutableListOf(
                        reseamBin.get().absolutePath,
                        "publish",
                        "patches",
                        bundleFile.get().asFile.absolutePath,
                        "--url",
                        url.orNull
                            ?: throw GradleException(
                                "RESEAM_BUNDLE_URL or -PreleaseTag is required"
                            ),
                        "--homepage",
                        homepage.get(),
                        "--out",
                        outFile.get().absolutePath,
                    )
                description
                    .get()
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        args += listOf("--description", it)
                    }
                descriptionFile.orNull?.let {
                    args += listOf("--description-file", it.absolutePath)
                }
                createdAt
                    .get()
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        args += listOf("--created-at", it)
                    }
                if (prerelease.get()) args += "--prerelease"
                outFile.get().parentFile.mkdirs()
                commandLine(args)
            }
        }

    tasks.register("stageRelease", Sync::class.java) {
        group = "distribution"
        description = "Collects the bundle and patches.json for a release."
        dependsOn(patchesJson)
        from(bundleFile)
        from(patchesJson.map { it.outputs.files })
        into(layout.buildDirectory.dir("reseam/release"))
    }
}
