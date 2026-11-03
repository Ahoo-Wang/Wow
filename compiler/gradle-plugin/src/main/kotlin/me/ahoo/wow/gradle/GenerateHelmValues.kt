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
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Writes the values of the `wow-service` Helm chart that the service's build knows, from its merged `manifest.json`
 * (the output of [MergeManifest]) and the peer contracts on its runtime class path: the service's name, after which the
 * chart names every object; the peers, the contexts of those contracts it does not host, each with the service its
 * contract names; and the route its nodes serve the Manifest at. The deployment's own values (the image, the replicas,
 * the resources, the stores and their credentials) go in a values file passed after this one, which overrides it. It
 * runs in an isolated worker with the tools, like [MergeManifest].
 */
@CacheableTask
internal abstract class GenerateHelmValues @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    /** The module's runtime class path, whose api jars carry the peers' contracts. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** The tools that read the contracts: the `wowTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.GenerateHelmValues::class.java) { parameters ->
            parameters.manifest.set(manifestFile)
            parameters.classpath.from(classpath)
            parameters.output.set(outputFile)
        }
    }
}
