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
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.workers.ProcessWorkerSpec
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Completes the module's Manifest fragment that `wow-compiler` wrote: fills in the schemas and the
 * state fingerprints in a worker process that holds the tools ([tools]) and the compiled classes, so neither the tools
 * nor the module's classes reach the build's class path. The worker runs on the module's Java toolchain ([launcher]):
 * it loads the classes the module compiled, which a JVM older than the toolchain cannot read, and the Gradle daemon
 * may be older (Gradle on 21, the module on 25). The completed fragment replaces the compiler's in the module's
 * resources.
 *
 * The other tasks of the plugin read Manifests and contracts as files and load no class of the module, so they stay in
 * the daemon's JVM, in a worker with an isolated class loader ([MergeManifest]).
 */
@CacheableTask
internal abstract class GenerateManifestFragment @Inject constructor(
    private val workers: WorkerExecutor
) : DefaultTask() {
    /** The fragments `wow-compiler` wrote, `META-INF/wow/manifest/<module>.json`. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fragments: ConfigurableFileCollection

    /** The module's compiled classes and runtime class path, which the worker loads the referred classes from. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** The introspection tools: the `wowTools` configuration. */
    @get:Classpath
    abstract val tools: ConfigurableFileCollection

    /**
     * The JVM the worker runs on: the module's Java toolchain, the one that compiled [classpath]'s classes. An input,
     * so the fragment is not reused from a build that ran on another JVM.
     */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    /** [WowExtension.schemaOverrides]. */
    @get:Input
    abstract val schemaOverrides: MapProperty<String, String>

    /** The resource directory the completed fragments go to, under `META-INF/wow/manifest/`. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        submit(workers.processIsolation(::isolate))
    }

    /** The worker process: the module's class path and the tools, on the JVM of the module's toolchain. */
    internal fun isolate(spec: ProcessWorkerSpec) {
        // The module's class path first: its classes are read with the Jackson it runs with.
        spec.classpath.from(classpath, tools)
        spec.forkOptions.executable(launcher.get().executablePath.asFile.absolutePath)
    }

    /** One work item per fragment, each completing it into [outputDirectory]. */
    internal fun submit(queue: WorkQueue) {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        fragments.files.sortedBy { it.invariantSeparatorsPath }.forEach { fragment ->
            queue.submit(ManifestToolsWork.CompleteFragment::class.java) { parameters ->
                parameters.fragment.set(fragment)
                // Its path below the fragments' directory, which names it: a nested project's is a directory's.
                val name = fragment.invariantSeparatorsPath.substringAfterLast("${DomainRole.FRAGMENT_RESOURCES}/")
                parameters.output.set(output.resolve("${DomainRole.FRAGMENT_RESOURCES}/$name"))
                parameters.schemaOverrides.set(schemaOverrides)
            }
        }
    }
}
