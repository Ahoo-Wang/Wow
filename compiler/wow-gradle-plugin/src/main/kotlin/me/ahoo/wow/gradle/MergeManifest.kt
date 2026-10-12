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

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Merges every Manifest fragment on the module's runtime class path into the service's `manifest.json` (decision
 * D74), for offline tools; the runtime merges the same fragments when it starts. A conflict between fragments
 * (`ManifestMerger`) fails the task, and so does a schema reference no fragment resolves (a module whose fragment
 * was not completed by this plugin). It runs in an isolated worker with the tools, like [GenerateManifestFragment].
 */
@CacheableTask
abstract class MergeManifest @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    /** The module's runtime class path, its own resources included. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** The tools that read and merge the fragments: the `wowManifestTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    @get:Input
    abstract val serviceName: Property<String>

    @get:Input
    @get:Optional
    abstract val serviceVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun merge() {
        workers.classLoaderIsolation { spec -> spec.classpath.from(tools) }
            .submit(ManifestToolsWork.Merge::class.java) { parameters ->
                parameters.classpath.from(classpath)
                parameters.serviceName.set(serviceName)
                parameters.serviceVersion.set(serviceVersion)
                parameters.output.set(outputFile)
            }
    }
}
