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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Generates the Kotlin sources of an api module's [contract], `META-INF/wow/contract.json` as
 * [WowPlugin.ASSEMBLE_CONTRACT_TASK] assembles it: per context the object
 * `<api package>.<Context>Contract` with the context's name and, per aggregate, its `TYPE`, `ref(tenantId, id)`,
 * ownership, snapshot and query models, and the `QueryField` constants of its state's paths, named as the compiler
 * names the domain module's `<State>Properties`; and per state the aggregate publishes, the view `<State>View` another
 * context reads it as, which references the classes of the [classpath] and copies the domain's own. The api module
 * compiles them in its [WowPlugin.CONTRACT_SOURCE_SET] source set and its jar carries them. No contract, no source.
 *
 * The query fields need `wow-query-api`, as the compiler's do: they are generated only when the [classpath] the code
 * compiles against holds it. The generator runs in a worker over [tools] (`wow-manifest-tools`).
 */
@CacheableTask
internal abstract class GenerateContractSources @Inject constructor(
    private val workers: WorkerExecutor
) : DefaultTask() {
    /** The api module's assembled contract: one file, or none when no context is the api module's. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val contract: ConfigurableFileCollection

    /** The class path the generated code compiles against. */
    @get:CompileClasspath
    abstract val classpath: ConfigurableFileCollection

    /** The generator: the `wowTools` configuration of the api module. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    /** The root of the generated Kotlin sources. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        submit(workers.classLoaderIsolation { spec -> spec.classpath.from(tools) })
    }

    /** One work item, writing the sources of [contract] into [outputDirectory]. */
    internal fun submit(queue: WorkQueue) {
        queue.submit(ManifestToolsWork.GenerateContractSources::class.java) { parameters ->
            parameters.contracts.from(contract)
            parameters.classpath.from(classpath)
            parameters.output.set(outputDirectory)
        }
    }
}
