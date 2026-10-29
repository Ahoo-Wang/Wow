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
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Merges every Manifest fragment on the module's runtime class path into the service's `manifest.json`,
 * for offline tools; the runtime merges the same fragments when it starts. A conflict between fragments
 * (`ManifestMerger`) fails the task, and so do two fragments of one name (two modules of one name, from two builds)
 * and a schema reference no fragment resolves (a module whose fragment was not completed by this plugin). A failure
 * names the projects and modules the fragments come from ([owners]). It runs in an isolated worker with the tools, like [GenerateManifestFragment].
 */
@CacheableTask
internal abstract class MergeManifest @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    /** The module's runtime class path, with its own completed fragment. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** The owner of each [classpath] entry, by its path, as a failure names it: a project or a module. */
    @get:Internal
    abstract val owners: MapProperty<String, String>

    /** The tools that read and merge the fragments: the `wowManifestTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    /** The service's name (`wow { serviceName }`); unset, the merge derives it ([defaultServiceName]). */
    @get:Input
    @get:Optional
    abstract val serviceName: Property<String>

    /**
     * The name of a service whose contexts' contracts name none, when [serviceName] is unset: the project's. The
     * contracts on the [classpath] that name one give it instead.
     */
    @get:Input
    abstract val defaultServiceName: Property<String>

    @get:Input
    @get:Optional
    abstract val serviceVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun merge() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.Merge::class.java) { parameters ->
            parameters.classpath.from(classpath)
            parameters.owners.set(owners)
            parameters.serviceName.set(serviceName)
            parameters.defaultServiceName.set(defaultServiceName)
            parameters.serviceVersion.set(serviceVersion)
            parameters.output.set(outputFile)
        }
    }
}
