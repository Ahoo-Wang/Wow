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

import me.ahoo.wow.manifest.Manifest
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Completes the module's Manifest fragment that `wow-compiler` wrote (decision D73): loads the compiled classes in a
 * worker with an isolated class loader, so neither they nor their dependencies reach the build's class path, and
 * fills in the schemas and the state fingerprints ([SchemaIntrospector]). The completed fragment replaces the
 * compiler's in the module's resources.
 */
@CacheableTask
abstract class GenerateManifestFragment @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
    /** The fragments `wow-compiler` wrote, `META-INF/wow/manifest/<module>.json`. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fragments: ConfigurableFileCollection

    /** The module's compiled classes and runtime class path, which the worker loads the referred classes from. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** [WowExtension.schemaOverrides]. */
    @get:Input
    abstract val schemaOverrides: MapProperty<String, String>

    /** The resource directory the completed fragments go to, under `META-INF/wow/manifest/`. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val queue = workers.classLoaderIsolation { spec -> spec.classpath.from(classpath) }
        fragments.files.sortedBy { it.name }.forEach { fragment ->
            queue.submit(CompleteFragment::class.java) { parameters ->
                parameters.fragment.set(fragment)
                parameters.output.set(output.resolve("${Manifest.FRAGMENT_DIRECTORY}/${fragment.name}"))
                parameters.schemaOverrides.set(schemaOverrides)
            }
        }
    }

    /** Runs in the isolated worker: its class loader holds the module's classes. */
    abstract class CompleteFragment : WorkAction<CompleteFragment.Parameters> {
        interface Parameters : WorkParameters {
            val fragment: RegularFileProperty
            val output: RegularFileProperty
            val schemaOverrides: MapProperty<String, String>
        }

        override fun execute() {
            val skeleton = ManifestJson.read(parameters.fragment.get().asFile)
            val introspector = SchemaIntrospector(javaClass.classLoader, parameters.schemaOverrides.get())
            ManifestJson.write(introspector.complete(skeleton), parameters.output.get().asFile)
        }
    }
}
