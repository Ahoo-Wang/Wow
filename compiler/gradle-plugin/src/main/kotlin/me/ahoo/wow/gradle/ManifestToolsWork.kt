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

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * The work the plugin hands to its isolated workers. The tools (`wow-manifest-tools`: `me.ahoo.wow.manifest.tools.ManifestTools`,
 * with victools, Jackson and the kernel) are on the worker's class path only, never on the plugin's, so these actions
 * reach them by name, from their own class loader, and pass JDK types only.
 */
internal object ManifestToolsWork {
    const val TOOLS = "me.ahoo.wow.manifest.tools.ManifestTools"

    fun call(loader: ClassLoader, method: String, vararg arguments: Any?): Any? {
        val tools = Class.forName(TOOLS, true, loader)
        val function = tools.methods.single { it.name == method }
        return try {
            function.invoke(null, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    /** Completes one fragment of the compiler with the schemas of the module's classes. */
    abstract class CompleteFragment : WorkAction<CompleteFragment.Parameters> {
        interface Parameters : WorkParameters {
            val fragment: RegularFileProperty
            val output: RegularFileProperty
            val schemaOverrides: MapProperty<String, String>
        }

        override fun execute() {
            call(
                javaClass.classLoader,
                "completeFragment",
                parameters.fragment.get().asFile,
                parameters.output.get().asFile,
                parameters.schemaOverrides.get(),
            )
        }
    }

    /** Merges the fragments of a class path into the service's Manifest. */
    abstract class Merge : WorkAction<Merge.Parameters> {
        interface Parameters : WorkParameters {
            val classpath: ConfigurableFileCollection
            val owners: MapProperty<String, String>
            val serviceName: Property<String>
            val defaultServiceName: Property<String>
            val serviceVersion: Property<String>
            val output: RegularFileProperty
        }

        override fun execute() {
            call(
                javaClass.classLoader,
                "merge",
                parameters.classpath.files.toList<File>(),
                parameters.owners.get(),
                parameters.serviceName.orNull,
                parameters.defaultServiceName.get(),
                parameters.serviceVersion.orNull,
                parameters.output.get().asFile,
            )
        }
    }

    /** Generates the Kotlin sources of an api module's contract (`ManifestTools.generateContractSources`). */
    abstract class GenerateContractSources : WorkAction<GenerateContractSources.Parameters> {
        interface Parameters : WorkParameters {
            val contracts: ConfigurableFileCollection
            val classpath: ConfigurableFileCollection
            val output: DirectoryProperty
        }

        override fun execute() {
            call(
                javaClass.classLoader,
                "generateContractSources",
                parameters.contracts.files.toList<File>(),
                parameters.classpath.files.toList<File>(),
                parameters.output.get().asFile,
            )
        }
    }

    /** Compares the service's Manifest with its baseline, and fails on a breaking change within the major version. */
    abstract class CheckCompatibility : WorkAction<CheckCompatibility.Parameters> {
        interface Parameters : WorkParameters {
            val baseline: RegularFileProperty
            val manifest: RegularFileProperty
            val report: RegularFileProperty
        }

        override fun execute() {
            call(
                javaClass.classLoader,
                "checkCompatibility",
                parameters.baseline.get().asFile,
                parameters.manifest.get().asFile,
                parameters.report.get().asFile,
            )
        }
    }
}
