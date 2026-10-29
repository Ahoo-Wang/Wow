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
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * The Manifest gate, part of `check`: compares the service's merged Manifest with its committed [baseline] and fails,
 * naming `updateWowManifestBaseline`, when there is no baseline, when a change breaks the contract (listing each
 * breaking change with its path, `contexts[shop].aggregates[cart].commands[AddItem]`), and when the baseline is not the
 * Manifest this build produces, its version aside: the baseline records every change, so its diff in review shows
 * what the clients will see. Every change, additive or breaking, goes to [report]. It runs in an isolated worker with
 * the tools, like [MergeManifest].
 */
@CacheableTask
internal abstract class CheckManifestCompatibility @Inject constructor(
    private val workers: WorkerExecutor
) : DefaultTask() {
    /** The committed baseline, [WowServiceExtension.manifestBaseline]; a missing one fails the gate. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    /** The service's merged Manifest, what [MergeManifest] writes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The tools that compare the Manifests: the `wowTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    /** The service module, as a failure names it: `project ':shop-server'`. */
    @get:Input
    abstract val owner: Property<String>

    /** The classified changes, the breaking ones first. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.CheckCompatibility::class.java) { parameters ->
            parameters.baseline.set(baseline)
            parameters.manifest.set(manifest)
            parameters.report.set(report)
            parameters.owner.set(owner)
        }
    }
}
