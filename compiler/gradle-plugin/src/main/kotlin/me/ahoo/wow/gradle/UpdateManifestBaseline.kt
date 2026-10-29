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
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Records the service's merged Manifest as its [baseline], the one [CheckManifestCompatibility] compares with: without
 * its service's version, which changes with every release, on the compatibility line of the service's major. Run it
 * after an intended change to the contract, and commit the file it writes. It refuses to record a breaking change
 * within the existing baseline's line (the service's next major version may break it, as may a pre-release of a
 * major's first version) unless [allowBreaking] (`-Pwow.manifest.allowBreaking`), which its [report] then records. It
 * runs in an isolated worker with the tools, like [MergeManifest].
 */
@DisableCachingByDefault(because = "Writes one file into the project's sources")
internal abstract class UpdateManifestBaseline @Inject constructor(
    private val workers: WorkerExecutor
) : DefaultTask() {
    /** The service's merged Manifest, what [MergeManifest] writes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The tools that write the baseline: the `wowTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    /** The committed baseline, [WowServiceExtension.manifestBaseline]. */
    @get:OutputFile
    abstract val baseline: RegularFileProperty

    /** Whether a breaking change within the line may be recorded: `-Pwow.manifest.allowBreaking`, for pre-releases. */
    @get:Input
    abstract val allowBreaking: Property<Boolean>

    /** The service module, as a failure names it: `project ':shop-server'`. */
    @get:Input
    abstract val owner: Property<String>

    /** What was recorded: the line, the changes, and the breaking ones [allowBreaking] let through. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun update() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.RecordBaseline::class.java) { parameters ->
            parameters.manifest.set(manifest)
            parameters.baseline.set(baseline)
            parameters.allowBreaking.set(allowBreaking)
            parameters.report.set(report)
            parameters.owner.set(owner)
        }
    }
}
