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

import com.google.devtools.ksp.gradle.KspExtension
import me.ahoo.wow.manifest.Manifest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import java.util.Properties

/**
 * `me.ahoo.wow`: builds a V10 domain module. Once the Kotlin JVM plugin is applied, it
 *
 * - applies KSP with `wow-compiler` (of the plugin's version, unless [WowExtension.addCompilerDependency] is false)
 *   in `generate` mode, so the module's aggregates compile into models and its Manifest fragment
 *   (`META-INF/wow/manifest/<project name>.json`, decision D74); only the V10 model processor runs, the 9.x ones
 *   (`wow-metadata.json`, query helpers) are excluded;
 * - registers [GENERATE_FRAGMENT_TASK] ([GenerateManifestFragment]), which completes the fragment with the
 *   schemas Jackson introspection gives (decision D73); the completed fragment is part of the module's resources in
 *   place of the compiler's;
 * - registers [MERGE_TASK] ([MergeManifest]), which merges every fragment on the runtime class path into
 *   `build/wow/manifest.json`.
 */
class WowPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(EXTENSION, WowExtension::class.java)
        extension.addCompilerDependency.convention(true)
        extension.serviceName.convention(project.name)
        extension.serviceVersion.convention(project.provider { project.version.toString() })
        project.pluginManager.withPlugin(KOTLIN_JVM) { configure(project, extension) }
    }

    private fun configure(project: Project, extension: WowExtension) {
        project.pluginManager.apply(KSP)
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg(MODEL_OPTION, "generate")
            ksp.arg(MODULE_OPTION, project.name)
            ksp.arg(ContextArgument(extension.context))
            // A V10 module is built by the model processor alone; the 9.x processors serve wow-core.
            NINE_X_PROCESSORS.forEach(ksp::excludeProcessor)
        }
        project.configurations.named(KSP_CONFIGURATION) { configuration ->
            configuration.withDependencies { dependencies ->
                if (extension.addCompilerDependency.get()) {
                    dependencies.add(project.dependencies.create("$GROUP:wow-compiler:$version"))
                }
            }
        }

        val main = project.extensions.getByType(
            SourceSetContainer::class.java
        ).getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val runtimeClasspath = project.configurations.named(main.runtimeClasspathConfigurationName)
        val compilerFragments = project.files(project.layout.buildDirectory.dir(KSP_RESOURCES))
            .builtBy(KSP_TASK)
            .asFileTree
            .matching { it.include("${Manifest.FRAGMENT_DIRECTORY}/*.json") }
        val fragmentDirectory = project.layout.buildDirectory.dir("wow/manifest-fragment")
        val generate = project.tasks.register(GENERATE_FRAGMENT_TASK, GenerateManifestFragment::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Completes this module's Manifest fragment with the schemas of its types."
            task.fragments.from(compilerFragments)
            task.classpath.from(main.output.classesDirs, runtimeClasspath)
            task.schemaOverrides.set(extension.schemaOverrides)
            task.outputDirectory.set(fragmentDirectory)
        }
        main.output.dir(mapOf("builtBy" to generate), fragmentDirectory)
        // The completed fragment takes the place of the compiler's.
        project.tasks.named(main.processResourcesTaskName, ProcessResources::class.java) {
            it.exclude("${Manifest.FRAGMENT_DIRECTORY}/**")
        }

        project.tasks.register(MERGE_TASK, MergeManifest::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Merges the Manifest fragments on the runtime class path into the service's manifest.json."
            task.classpath.from(main.output, runtimeClasspath)
            task.serviceName.set(extension.serviceName)
            task.serviceVersion.set(extension.serviceVersion)
            task.outputFile.set(project.layout.buildDirectory.file("wow/manifest.json"))
        }
    }

    /** `wow.context`, only when [WowExtension.context] is set: otherwise the compiler takes the `@BoundedContext`. */
    private class ContextArgument(
        @get:Input @get:Optional val context: Provider<String>
    ) : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOfNotNull(context.orNull?.let { "$CONTEXT_OPTION=$it" })
    }

    companion object {
        const val EXTENSION = "wow"
        const val GENERATE_FRAGMENT_TASK = "generateWowManifestFragment"
        const val MERGE_TASK = "mergeWowManifest"
        private const val GROUP_NAME = "wow"
        private const val GROUP = "me.ahoo.wow"
        private const val KOTLIN_JVM = "org.jetbrains.kotlin.jvm"
        private const val KSP = "com.google.devtools.ksp"
        private const val KSP_CONFIGURATION = "ksp"
        private const val KSP_TASK = "kspKotlin"
        private const val KSP_RESOURCES = "generated/ksp/main/resources"
        private const val MODEL_OPTION = "wow.model"
        private const val MODULE_OPTION = "wow.manifest.module"
        private const val CONTEXT_OPTION = "wow.context"
        private val NINE_X_PROCESSORS = listOf(
            "me.ahoo.wow.compiler.metadata.MetadataSymbolProcessorProvider",
            "me.ahoo.wow.compiler.query.QuerySymbolProcessorProvider",
            "me.ahoo.wow.compiler.aggregate.metadata.AggregatesMetadataSymbolProcessorProvider",
        )

        /** The plugin's version, which is also the version of `wow-compiler` it adds. */
        val version: String by lazy {
            Properties().apply {
                WowPlugin::class.java.getResourceAsStream("/META-INF/wow-gradle-plugin.properties")!!.use(::load)
            }.getProperty("version")
        }
    }
}
