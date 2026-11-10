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

import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import me.ahoo.wow.gradle.WowArtifacts.GROUP
import me.ahoo.wow.gradle.WowArtifacts.declares
import me.ahoo.wow.gradle.WowArtifacts.documentationAttributes
import me.ahoo.wow.gradle.WowArtifacts.toolsConfiguration
import me.ahoo.wow.gradle.WowArtifacts.wowDependencies
import me.ahoo.wow.gradle.WowPlugin.Companion.API_VIEW
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_DOCS_TYPE
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_ELEMENTS
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_CONTRACT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_FRAGMENT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.KOTLIN_JVM
import me.ahoo.wow.gradle.WowPlugin.Companion.coreView
import me.ahoo.wow.gradle.WowPlugin.Companion.fragmentName
import me.ahoo.wow.gradle.WowPlugin.Companion.missingKotlinJvm
import me.ahoo.wow.gradle.WowPlugin.Companion.requireKotlin
import me.ahoo.wow.gradle.WowPlugin.Companion.version
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider

/**
 * The domain role of a project ([domain]: `me.ahoo.wow`, and `me.ahoo.wow.service` on its own sources): the Wow
 * dependencies, KSP with the model processor, the module's Manifest fragment completed with its schemas, and the peer
 * contract its api modules carry.
 */
internal object DomainRole {
    /**
     * The domain role of [project], applied by the plugin [id]: once the Kotlin JVM plugin is applied, the Wow
     * dependencies, KSP, the fragment and the contract. Without the Kotlin JVM plugin by the end of its evaluation the
     * project fails, naming the fix.
     */
    fun domain(project: Project, extension: WowExtension, id: String) {
        project.pluginManager.withPlugin(KOTLIN_JVM) { configureDomain(project, extension) }
        if (project.state.executed) {
            requireKotlinJvm(project, id)
        } else {
            project.afterEvaluate { requireKotlinJvm(it, id) }
        }
    }

    private fun requireKotlinJvm(project: Project, id: String) {
        if (!project.pluginManager.hasPlugin(KOTLIN_JVM)) {
            throw GradleException(missingKotlinJvm(project.path, id))
        }
    }

    private fun configureDomain(project: Project, extension: WowExtension) {
        requireKotlin(project.path, kotlinPluginVersion(project))
        wowDependencies(project, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, KERNEL)
        configureKsp(project, extension)

        val tools = toolsConfiguration(project)
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val runtimeClasspath = project.configurations.named(main.runtimeClasspathConfigurationName)
        val compileClasspath = project.configurations.getByName(main.compileClasspathConfigurationName)
        coreRequest(project, compileClasspath)
        // KSP runs within the Kotlin compilation; it has no public API for its task or its output directory, so they
        // are named here alone. A KSP that renames them fails the plugin's functional tests.
        val compilerFragments = project.files(project.layout.buildDirectory.dir(KSP_RESOURCES))
            .builtBy(KSP_TASK)
            .asFileTree
            .matching { it.include("${FRAGMENT_RESOURCES}/**/*.json") }
        val fragmentDirectory = project.layout.buildDirectory.dir(FRAGMENT_DIRECTORY)
        val generate = project.tasks.register(GENERATE_FRAGMENT_TASK, GenerateManifestFragment::class.java) { task ->
            task.description = "Completes this module's Manifest fragment with the schemas of its types."
            task.fragments.from(compilerFragments)
            task.classpath.from(main.output.classesDirs, classesOf(project, runtimeClasspath.get()))
            task.tools.from(tools)
            task.schemaOverrides.set(extension.schemaOverrides)
            task.outputDirectory.set(fragmentDirectory)
        }
        main.output.dir(mapOf("builtBy" to generate), fragmentDirectory)
        // The completed fragment takes the place of the compiler's.
        project.tasks.named(main.processResourcesTaskName, ProcessResources::class.java) {
            it.exclude("${FRAGMENT_RESOURCES}/**")
        }
        contract(project, project.files(fragmentDirectory).builtBy(generate).asFileTree, extension, compileClasspath)
    }

    /**
     * The classes variants of [classpath]: a project's classes directories, never its jar, and a module's jar. The
     * jar of an api module carries the contract of the domain modules that pair with it, so reading it to complete
     * their fragments would be a cycle.
     */
    private fun classesOf(project: Project, classpath: Configuration): FileCollection =
        classpath.incoming.artifactView { view ->
            view.attributes {
                it.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.objects.named(LibraryElements::class.java, LibraryElements.CLASSES),
                )
                it.attribute(API_VIEW, coreView(project.path))
            }
        }.files

    /**
     * The peer contract: [GENERATE_CONTRACT_TASK] filters the completed [fragments], refusing a context whose classes
     * are in a project of [compileClasspath] that does not pair with the module, and the [CONTRACT_ELEMENTS] variant
     * offers the result, empty for a module of no fragment.
     */
    private fun contract(
        project: Project,
        fragments: FileTree,
        extension: WowExtension,
        compileClasspath: Configuration,
    ) {
        val unpaired = unpairedProjectsOf(compileClasspath)
        val generate = project.tasks.register(GENERATE_CONTRACT_TASK, GenerateContract::class.java) { task ->
            task.description = "Filters the module's Manifest fragment down to the contract its api modules carry."
            task.fragments.from(fragments.matching { it.include("${FRAGMENT_RESOURCES}/**/*.json") })
            task.service.set(extension.service)
            task.module.set(project.path)
            task.unpaired.set(unpaired)
            task.unpairedProjects.set(unpaired.map { it.values.distinct().sorted() })
            task.tools.from(toolsConfiguration(project))
            task.outputFile.set(project.layout.buildDirectory.file("wow/contract.json"))
        }
        val elements = project.configurations.create(CONTRACT_ELEMENTS) { configuration ->
            configuration.description = "The peer contract of the module's contexts, which its api modules carry."
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            documentationAttributes(project, configuration, CONTRACT_DOCS_TYPE)
        }
        project.artifacts.add(elements.name, generate.flatMap { it.outputFile }) { it.builtBy(generate) }
    }

    /**
     * The project entries of [compileClasspath] whose variant is not a core view ([API_VIEW]): projects that do not
     * pair with the module, by entry path. Only the module's contract task reads it, once the classes are built.
     */
    private fun unpairedProjectsOf(compileClasspath: Configuration): Provider<Map<String, String>> =
        compileClasspath.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.mapNotNull { artifact ->
                val id = artifact.id.componentIdentifier as? ProjectComponentIdentifier ?: return@mapNotNull null
                if (artifact.variant.attributes.getAttribute(API_VIEW) != null) return@mapNotNull null
                artifact.file.path to id.projectPath
            }.toMap()
        }

    /**
     * Makes [configuration] of a domain module request its core view of its api modules ([coreView]): they carry the
     * code generated from the domain's contract, which compiling and processing the domain cannot wait for. KSP reads
     * the compile class path too.
     */
    private fun coreRequest(project: Project, configuration: Configuration) {
        project.dependencies.attributesSchema.attribute(API_VIEW)
        configuration.attributes { it.attribute(API_VIEW, coreView(project.path)) }
    }

    /**
     * Applies KSP with the model processor, and adds `wow-compiler` of the plugin's version unless `ksp` declares
     * `wow-compiler` itself (a build that pins it, or Wow's own build). Its KSP tasks run one at a time ([KspRuns]).
     */
    private fun configureKsp(project: Project, extension: WowExtension) {
        project.pluginManager.apply(KSP)
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg(MODEL_OPTION, "generate")
            ksp.arg(MODULE_OPTION, fragmentName(project.path))
            ksp.arg(ContextArgument(extension.context))
        }
        val runs = KspRuns.of(project)
        project.tasks.withType(KspAATask::class.java).configureEach { it.usesService(runs) }
        // Added at once, not when `ksp` resolves: KSP wires a compilation's processors only when it sees a declared
        // one. A wow-compiler the module declares itself takes its place.
        project.configurations.named(KSP_CONFIGURATION) { configuration ->
            val compiler = project.dependencies.create("$GROUP:$COMPILER:$version")
            configuration.dependencies.add(compiler)
            configuration.withDependencies { dependencies ->
                if (dependencies.any { it !== compiler && declares(it, COMPILER) }) dependencies.remove(compiler)
            }
        }
    }

    /** The version of the Kotlin Gradle plugin [project] applies, read through the plugin's own class loader. */
    private fun kotlinPluginVersion(project: Project): String {
        val loader = project.plugins.getPlugin(KOTLIN_JVM).javaClass.classLoader
        return Class.forName(KOTLIN_WRAPPER, false, loader).getMethod("getKotlinPluginVersion", Project::class.java)
            .invoke(null, project) as String
    }

    /** `wow.context`, only when [WowExtension.context] is set: otherwise the compiler takes the `@BoundedContext`. */
    internal class ContextArgument(
        @get:Input @get:Optional val context: Provider<String>
    ) : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOfNotNull(context.orNull?.let { "$CONTEXT_OPTION=$it" })
    }

    private const val COMPILER = "wow-compiler"
    private const val KERNEL = "wow-kernel"
    internal const val FRAGMENT_DIRECTORY = "wow/manifest-fragment"

    /** Where the compiler writes a module's Manifest fragments, and where a jar carries them. */
    internal const val FRAGMENT_RESOURCES = "META-INF/wow/manifest"

    private const val KOTLIN_WRAPPER = "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt"
    private const val KSP = "com.google.devtools.ksp"
    private const val KSP_CONFIGURATION = "ksp"
    private const val KSP_TASK = "kspKotlin"
    private const val KSP_RESOURCES = "generated/ksp/main/resources"
    private const val MODEL_OPTION = "wow.model"
    private const val MODULE_OPTION = "wow.manifest.module"
    private const val CONTEXT_OPTION = "wow.context"
}
