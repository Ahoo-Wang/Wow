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

import me.ahoo.wow.gradle.WowArtifacts.GROUP
import me.ahoo.wow.gradle.WowArtifacts.declares
import me.ahoo.wow.gradle.WowArtifacts.documentationAttributes
import me.ahoo.wow.gradle.WowArtifacts.toolsConfiguration
import me.ahoo.wow.gradle.WowArtifacts.wowDependencies
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_DOCS_TYPE
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_ELEMENTS
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_CONTRACT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_FRAGMENT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.KOTLIN_JVM
import me.ahoo.wow.gradle.WowPlugin.Companion.fragmentName
import me.ahoo.wow.gradle.WowPlugin.Companion.kspVersion
import me.ahoo.wow.gradle.WowPlugin.Companion.missingKotlinJvm
import me.ahoo.wow.gradle.WowPlugin.Companion.missingKsp
import me.ahoo.wow.gradle.WowPlugin.Companion.requireKotlin
import me.ahoo.wow.gradle.WowPlugin.Companion.version
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.UnknownPluginException
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaToolchainService
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
        project.pluginManager.withPlugin(KOTLIN_JVM) { configureDomain(project, extension, id) }
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

    private fun configureDomain(project: Project, extension: WowExtension, id: String) {
        requireKotlin(project.path, kotlinPluginVersion(project))
        wowDependencies(project, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, KERNEL)
        configureKsp(project, extension, id)
        // Its tests run Wow's JSON, and a service's runtime too.
        CheckJackson.register(project, service = id == WowPlugin.SERVICE_ID)

        val tools = toolsConfiguration(project)
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val runtimeClasspath = project.configurations.named(main.runtimeClasspathConfigurationName)
        val compileClasspath = project.configurations.getByName(main.compileClasspathConfigurationName)
        // The tests see the contracts of the build's api modules on their class paths, their own api's included: most
        // use its <Context>Contract. Main never does: the module's contract comes from compiling it.
        // A service module derives them for every source set itself (ServiceRole), published peers' included.
        if (id != WowPlugin.SERVICE_ID) {
            ContractDerivation.derive(project, projectsOnly = true) { it.name != SourceSet.MAIN_SOURCE_SET_NAME }
        }
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
            // The module's classes are read on the JVM that compiled them, not on the Gradle daemon's.
            task.launcher.set(
                project.extensions.getByType(JavaToolchainService::class.java)
                    .launcherFor(project.extensions.getByType(JavaPluginExtension::class.java).toolchain),
            )
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
     * The classes variants of [classpath]: a project's classes directories, never its jar, and a module's jar. An api
     * module's are its types alone, never its contract, which comes from completing these fragments.
     */
    private fun classesOf(project: Project, classpath: Configuration): FileCollection =
        classpath.incoming.artifactView { view ->
            view.attributes {
                it.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.objects.named(LibraryElements::class.java, LibraryElements.CLASSES),
                )
            }
        }.files

    /**
     * The peer contract: [GENERATE_CONTRACT_TASK] filters the completed [fragments], refusing a context whose classes
     * are in a project of [compileClasspath] that does not pair with the module ([pairingOf]), and the
     * [CONTRACT_ELEMENTS] variant offers the result, empty for a module of no fragment.
     */
    private fun contract(
        project: Project,
        fragments: FileTree,
        extension: WowExtension,
        compileClasspath: Configuration,
    ) {
        val projects = projectsOf(compileClasspath)
        val pairing = pairingOf(project, compileClasspath)
        val generate = project.tasks.register(GENERATE_CONTRACT_TASK, GenerateContract::class.java) { task ->
            task.description = "Filters the module's Manifest fragment down to the contract its api modules carry."
            task.fragments.from(fragments.matching { it.include("${FRAGMENT_RESOURCES}/**/*.json") })
            task.service.set(extension.service)
            task.contractPackage.set(extension.contractPackage)
            task.module.set(project.path)
            task.classpathProjects.set(projects)
            task.classpathProjectPaths.set(projects.map { it.values.distinct().sorted() })
            val pairings = pairing.artifacts.resolvedArtifacts.map(::pairings)
            task.pairing.from(pairings.map { it.keys })
            task.pairingProjects.set(pairings)
            task.tools.from(toolsConfiguration(project))
            task.outputFile.set(project.layout.buildDirectory.file("wow/contract.json"))
            task.packagesFile.set(project.layout.buildDirectory.file("wow/${ApiRole.CONTRACT_PACKAGES_FILE}"))
        }
        val elements = project.configurations.create(CONTRACT_ELEMENTS) { configuration ->
            configuration.description = "The peer contract of the module's contexts, which its api modules carry."
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            documentationAttributes(project, configuration, CONTRACT_DOCS_TYPE)
        }
        project.artifacts.add(elements.name, generate.flatMap { it.outputFile }) { it.builtBy(generate) }
        project.artifacts.add(elements.name, generate.flatMap { it.packagesFile }) { it.builtBy(generate) }
    }

    /** The project entries of [compileClasspath], each to its project's path. */
    private fun projectsOf(compileClasspath: Configuration): Provider<Map<String, String>> =
        compileClasspath.incoming.artifacts.resolvedArtifacts.map(::byProject)

    /** The files of [artifacts] that come from projects of the build, each to its project's path. */
    private fun byProject(artifacts: Collection<ResolvedArtifactResult>): Map<String, String> =
        artifacts.mapNotNull { artifact ->
            val id = artifact.id.componentIdentifier as? ProjectComponentIdentifier ?: return@mapNotNull null
            artifact.file.path to id.projectPath
        }.toMap()

    /** The pairing files among [artifacts] ([isPairing]), each to the project it comes from. */
    internal fun pairings(artifacts: Collection<ResolvedArtifactResult>): Map<String, String> =
        byProject(artifacts.filter(::isPairing))

    /**
     * The pairing of the api modules among [compileClasspath]'s projects ([ApiRole.PAIRING_ELEMENTS]): a file each,
     * naming the domain modules it declares. A project with none (no api module, or one of another build) pairs with
     * no domain: its variant is missing, which the lenient view leaves out.
     */
    internal fun pairingOf(project: Project, compileClasspath: Configuration): ArtifactView =
        compileClasspath.incoming.artifactView { view ->
            view.withVariantReselection()
            view.lenient(true)
            view.componentFilter { it is ProjectComponentIdentifier }
            view.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, ApiRole.PAIRING_USAGE))
                it.attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.objects.named(Category::class.java, ApiRole.PAIRING_USAGE),
                )
            }
        }

    /**
     * Whether [artifact] is a pairing file: of the pairing usage. A variant that declares neither usage nor category
     * (a project's source directories) matches the view too, and is no pairing.
     */
    private fun isPairing(artifact: ResolvedArtifactResult): Boolean =
        artifact.variant.attributes.keySet().any { key ->
            key.name == Usage.USAGE_ATTRIBUTE.name &&
                artifact.variant.attributes.getAttribute(key).toString() == ApiRole.PAIRING_USAGE
        }

    /**
     * Applies KSP with the model processor, and adds `wow-compiler` of the plugin's version unless `ksp` declares
     * `wow-compiler` itself (a build that pins it, or Wow's own build). KSP comes from the class path of [project]: the
     * markers of the domain and service plugins bring it, the plugin's jar does not, so `me.ahoo.wow.api` on a parent
     * build script puts no KSP beside a module's own (a 9.x module of a staged migration). Without it, the module fails
     * naming the line to add ([missingKsp]).
     */
    private fun configureKsp(project: Project, extension: WowExtension, id: String) {
        applyKsp(project, id)
        kspArg(project, MODEL_OPTION, "generate")
        kspArg(project, MODULE_OPTION, fragmentName(project.path))
        kspArg(project, ContextArgument(extension.context))
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

    /**
     * Applies the KSP plugin [ksp] to [project], which applies the Wow plugin [id]: by its id, from the project's own
     * class path. A class path without it fails naming the line to add ([missingKsp]).
     */
    internal fun applyKsp(project: Project, id: String, ksp: String = KSP) {
        try {
            project.pluginManager.apply(ksp)
        } catch (unknown: UnknownPluginException) {
            throw GradleException(missingKsp(project.path, id), unknown)
        }
    }

    /** Calls `arg` of [project]'s `ksp` extension with [arguments] ([kspArg]). */
    private fun kspArg(project: Project, vararg arguments: Any) {
        kspArg(project.extensions.getByName(KSP_EXTENSION), *arguments)
    }

    /**
     * Calls `arg` of the `ksp` extension [ksp] with [arguments] (a key and a value, or a [CommandLineArgumentProvider]),
     * by its public signature. Not through a typed reference, compile-only or not: the plugin's classes may be loaded
     * by a parent build script's class loader (a root build's `id("me.ahoo.wow.api") apply false`) that cannot see the
     * KSP the module's own class path holds, so a `KspExtension` the plugin names would fail to load, or be another
     * class than the module's. A KSP whose extension lacks that `arg` fails naming its version and the plugin's
     * ([kspSignatureMissing]).
     */
    internal fun kspArg(ksp: Any, vararg arguments: Any) {
        val types = arguments.map { argument ->
            if (argument is CommandLineArgumentProvider) CommandLineArgumentProvider::class.java else argument.javaClass
        }
        val arg = try {
            ksp.javaClass.getMethod("arg", *types.toTypedArray())
        } catch (missing: NoSuchMethodException) {
            throw GradleException(kspSignatureMissing(versionOf(ksp.javaClass), types.map { it.simpleName }), missing)
        }
        arg.invoke(ksp, *arguments)
    }

    /**
     * The version of the KSP Gradle plugin [type] (its extension, decorated by Gradle) comes from: the name of the jar
     * its class file is in (`symbol-processing-gradle-plugin-<version>.jar`), else its manifest's; null when neither
     * says.
     */
    internal fun versionOf(type: Class<*>): String? {
        val declared = generateSequence(type) { it.superclass }.first { !it.name.endsWith("_Decorated") }
        val location = declared.getResource("${declared.simpleName}.class")?.toString().orEmpty()
        return KSP_JAR.find(location)?.let { it.groupValues[1] } ?: declared.`package`.implementationVersion
    }

    /** Why a module fails whose KSP, of version [found], has no `arg` taking [parameters]. */
    internal fun kspSignatureMissing(found: String?, parameters: List<String>): String =
        "The KSP Gradle plugin ${found ?: "of an unknown version"} has no ksp { arg(${parameters.joinToString()}) }, " +
            "through which the Wow plugin ($version) passes the Wow compiler its options; the plugin is built for " +
            "KSP $kspVersion. Apply KSP $kspVersion (id(\"com.google.devtools.ksp\") version \"$kspVersion\"), or a " +
            "Wow plugin built for the KSP the build applies."

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
    private const val KSP_EXTENSION = "ksp"
    private val KSP_JAR = Regex("""symbol-processing-gradle-plugin-([^/!]+)\.jar""")
    private const val KSP_TASK = "kspKotlin"
    private const val KSP_RESOURCES = "generated/ksp/main/resources"
    private const val MODEL_OPTION = "wow.model"
    private const val MODULE_OPTION = "wow.manifest.module"
    private const val CONTEXT_OPTION = "wow.context"
}
