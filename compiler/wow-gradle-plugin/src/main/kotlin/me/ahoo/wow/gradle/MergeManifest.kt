/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.gradle

import me.ahoo.wow.manifest.ManifestMerger
import me.ahoo.wow.manifest.ServiceInfo
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Merges every Manifest fragment on the module's runtime class path into the service's `manifest.json` (decision
 * D74), for offline tools; the runtime merges the same fragments when it starts. A conflict between fragments
 * ([ManifestMerger]) fails the task, and so does a schema reference no fragment resolves (a module whose fragment
 * was not completed by this plugin).
 */
@CacheableTask
abstract class MergeManifest : DefaultTask() {
    /** The module's runtime class path, its own resources included. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val serviceName: Property<String>

    @get:Input
    @get:Optional
    abstract val serviceVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun merge() {
        val fragments = classpath.files.flatMap(ManifestJson::fragments)
        val manifest = try {
            ManifestMerger.merge(fragments, ServiceInfo(serviceName.get(), serviceVersion.orNull))
        } catch (conflict: IllegalStateException) {
            throw GradleException(conflict.message.orEmpty(), conflict)
        }
        val unresolved = manifest.unresolvedSchemaRefs
        if (unresolved.isNotEmpty()) {
            throw GradleException(
                "The Manifest refers to schemas no fragment holds: $unresolved. Apply the me.ahoo.wow plugin to " +
                    "the modules that declare them, so their fragments are completed.",
            )
        }
        ManifestJson.write(manifest, outputFile.get().asFile)
        logger.info("Merged {} Manifest fragment(s) into {}", fragments.size, outputFile.get().asFile)
    }
}
