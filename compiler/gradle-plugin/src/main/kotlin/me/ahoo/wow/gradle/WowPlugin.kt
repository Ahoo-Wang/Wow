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
import me.ahoo.wow.manifest.PeerContracts
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationVariant
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import java.util.Properties

/**
 * `me.ahoo.wow`: builds a domain module. It needs the Kotlin JVM plugin (`kotlin("jvm")`), also for a module of
 * Java sources only: KSP processes the module's Java sources within the Kotlin compilation, and the models it
 * generates are Kotlin. A module that applies this plugin without it fails to configure, naming the fix, instead of
 * building without models. Wow builds with Gradle only. Once the Kotlin JVM
 * plugin is applied, it
 *
 * - applies KSP with `wow-compiler` (of the plugin's version, unless [WowExtension.addCompilerDependency] is false)
 *   in `generate` mode, so the module's aggregates compile into models and its Manifest fragment
 *   (`META-INF/wow/manifest/<project name>.json`); only the model processor runs, the 9.x ones
 *   (`wow-metadata.json`, query helpers) are excluded;
 * - registers [GENERATE_FRAGMENT_TASK] ([GenerateManifestFragment]), which completes the fragment with the
 *   schemas Jackson introspection gives; the completed fragment is part of the module's resources in
 *   place of the compiler's;
 * - registers [MERGE_TASK] ([MergeManifest]), which merges every fragment on the runtime class path into
 *   `build/wow/manifest.json`, and exposes that file as the outgoing variant [MANIFEST_ELEMENTS], published with
 *   the `java` component;
 * - registers [OPENAPI_TASK] ([GenerateOpenApi]), which writes the OpenAPI document of that Manifest's routes into
 *   `build/wow/openapi.json` (.2);
 * - registers [CHECK_COMPATIBILITY_TASK] ([CheckManifestCompatibility]), part of `check`, which compares the merged
 *   Manifest with the committed baseline (`manifest/baseline.json`, [WowExtension.manifestBaseline]) and fails on a
 *   breaking change within the major version, and [UPDATE_BASELINE_TASK] ([UpdateManifestBaseline]),
 *   which records the merged Manifest as the baseline;
 * - registers [GENERATE_CONTRACT_TASK] ([GenerateContract]), which filters the completed fragment down to the peer
 *   contract of the module's contexts, offered as the variant [CONTRACT_ELEMENTS]; each project the
 *   module declares in `api(...)` is its api module, whose jar carries that contract at `META-INF/wow/contract.json`:
 *   the plugin gives it the configuration [CONTRACT_CONFIGURATION] and the task [ASSEMBLE_CONTRACT_TASK]
 *   ([AssembleContract]), which fails when a context's command and event classes are not all in the api module.
 *   A service then knows a peer context by depending on its api module alone. An api module with the Kotlin JVM
 *   plugin also compiles `<package>.<Context>Contract` per context from its contract ([GenerateContractSources],
 *   source set [CONTRACT_SOURCE_SET]): the context's name and, per aggregate, its type, refs,
 *   ownership, query models and, with `wow-query-api` on its class path, the query fields of its state, named as the
 *   compiler names the domain module's `<State>Properties`. Its classes and jar carry that code; its paired domain
 *   modules compile against a core view of it without that code (attribute [API_VIEW]), every other consumer against
 *   all of it. The generator runs in a worker over the api module's own [TOOLS_CONFIGURATION].
 *
 * The pairing configures the api project from the domain module's, which Gradle's isolated projects forbid; the
 * plugin is not compatible with them (a settings plugin pairing `x-api` with `x-domain` by name takes
 * its place once they are the default). The configuration cache is supported.
 *
 * The fragment, merge and compatibility tasks run their work in workers with an isolated class loader over
 * [TOOLS_CONFIGURATION]: the plugin itself depends on KSP and `wow-manifest` only, so it brings no Jackson, victools
 * or Kotlin reflection to the build script.
 */
class WowPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(EXTENSION, WowExtension::class.java)
        extension.addCompilerDependency.convention(true)
        extension.serviceName.convention(project.name)
        extension.serviceVersion.convention(project.provider { project.version.toString() })
        extension.manifestBaseline.convention(project.layout.projectDirectory.file(BASELINE_FILE))
        project.pluginManager.withPlugin(KOTLIN_JVM) { configure(project, extension) }
        if (project.state.executed) {
            requireKotlinJvm(project)
        } else {
            project.afterEvaluate(::requireKotlinJvm)
        }
    }

    /**
     * Fails the build of a module that applies the plugin without the Kotlin JVM plugin: KSP runs
     * only within a Kotlin compilation, and the models it generates are Kotlin, so such a module, a pure Java one in
     * particular, would otherwise get no models and no Manifest fragment, silently. Its Java sources are processed
     * once it applies `kotlin("jvm")` too.
     */
    private fun requireKotlinJvm(project: Project) {
        if (!project.pluginManager.hasPlugin(KOTLIN_JVM)) {
            throw GradleException(missingKotlinJvm(project.path))
        }
    }

    private fun configure(project: Project, extension: WowExtension) {
        configureKsp(project, extension)

        val tools = toolsConfiguration(project)
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val runtimeClasspath = project.configurations.named(main.runtimeClasspathConfigurationName)
        coreRequest(project, project.configurations.getByName(main.compileClasspathConfigurationName))
        val compilerFragments = project.files(project.layout.buildDirectory.dir(KSP_RESOURCES))
            .builtBy(KSP_TASK)
            .asFileTree
            .matching { it.include("${Manifest.FRAGMENT_DIRECTORY}/*.json") }
        val fragmentDirectory = project.layout.buildDirectory.dir("wow/manifest-fragment")
        val generate = project.tasks.register(GENERATE_FRAGMENT_TASK, GenerateManifestFragment::class.java) { task ->
            task.group = GROUP_NAME
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
            it.exclude("${Manifest.FRAGMENT_DIRECTORY}/**")
        }

        val merge = project.tasks.register(MERGE_TASK, MergeManifest::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Merges the Manifest fragments on the runtime class path into the service's manifest.json."
            // The module's own completed fragment and its dependencies'.
            task.classpath.from(project.files(fragmentDirectory).builtBy(generate), runtimeClasspath)
            task.tools.from(tools)
            task.serviceName.set(extension.serviceName)
            task.serviceVersion.set(extension.serviceVersion)
            task.outputFile.set(project.layout.buildDirectory.file("wow/manifest.json"))
        }
        mergedOutputs(project, merge)
        compatibility(project, extension, merge, tools)

        contract(project, project.files(fragmentDirectory).builtBy(generate).asFileTree, extension)
    }

    /**
     * The classes variants of [classpath]: a project's classes directories, never its jar, and a module's jar. The
     * jar of an api module carries the contract of the domain modules that depend on it, so reading it to complete
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
     * The peer contract: [GENERATE_CONTRACT_TASK] filters the completed [fragments], the
     * [CONTRACT_ELEMENTS] variant offers the result, and every project dependency of `api` is paired as the
     * module's api module ([pairApi]).
     */
    private fun contract(project: Project, fragments: FileTree, extension: WowExtension) {
        val generate = project.tasks.register(GENERATE_CONTRACT_TASK, GenerateContract::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Filters the module's Manifest fragment down to the contract its api modules carry."
            task.fragments.from(fragments.matching { it.include("${Manifest.FRAGMENT_DIRECTORY}/*.json") })
            task.service.set(extension.contract.service)
            task.outputFile.set(project.layout.buildDirectory.file("wow/contract.json"))
        }
        val elements = project.configurations.create(CONTRACT_ELEMENTS) { configuration ->
            configuration.description = "The peer contract of the module's contexts, which its api modules carry."
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            configuration.isVisible = false
            documentationAttributes(project, configuration, CONTRACT_DOCS_TYPE)
        }
        project.artifacts.add(elements.name, generate.flatMap { it.outputFile }) { it.builtBy(generate) }
        project.configurations.matching { it.name == API_CONFIGURATION }.configureEach { api ->
            api.dependencies.withType(ProjectDependency::class.java).configureEach { dependency ->
                pairApi(project.project(dependency.path), project.path)
            }
        }
    }

    /**
     * Makes [api] carry the contract of the domain module [domain]: its [CONTRACT_CONFIGURATION] resolves the
     * domain's [CONTRACT_ELEMENTS], and its jar includes what [ASSEMBLE_CONTRACT_TASK] keeps. Several domain modules
     * may pair with one api module.
     *
     * The api module becomes a `java-library`: its consumers then compile against its classes, never its jar, which
     * waits for the domain's contract, so compiling the domain module needs no jar of its api module.
     */
    private fun pairApi(api: Project, domain: String) {
        api.pluginManager.withPlugin("java") {
            api.pluginManager.apply(JavaLibraryPlugin::class.java)
            val contracts = api.configurations.findByName(CONTRACT_CONFIGURATION) ?: assembly(api)
            api.dependencies.add(contracts.name, api.project(domain))
            api.pluginManager.withPlugin(KOTLIN_JVM) { evaluated(api) { coreView(api, domain) } }
        }
    }

    private fun assembly(api: Project): Configuration {
        val contracts = api.configurations.create(CONTRACT_CONFIGURATION) { configuration ->
            configuration.description = "The peer contracts of the domain modules whose api module this is."
            configuration.isCanBeConsumed = false
            configuration.isTransitive = false
            configuration.isVisible = false
            documentationAttributes(api, configuration, CONTRACT_DOCS_TYPE)
        }
        val main = api.extensions.getByType(SourceSetContainer::class.java).getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val assemble = api.tasks.register(ASSEMBLE_CONTRACT_TASK, AssembleContract::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Takes the contracts of this api module's contexts from the domain modules that declare it."
            task.contracts.from(contracts)
            task.classes.from(main.output.classesDirs)
            task.owner.set(api.path)
            task.outputDirectory.set(api.layout.buildDirectory.dir("wow/contract"))
        }
        api.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java) { it.from(assemble) }
        api.pluginManager.withPlugin(KOTLIN_JVM) { evaluated(api) { contractSources(api, main, assemble) } }
        return contracts
    }

    /**
     * The code generated from [api]'s contract: [GENERATE_CONTRACT_SOURCES_TASK]
     * ([GenerateContractSources]) writes it from what [assemble] keeps, and the source set [CONTRACT_SOURCE_SET]
     * compiles it against [main]'s classes and dependencies. Its classes join the api's classes variants and jar, so
     * every consumer sees them. The domain modules, whose contracts they come from, compile, are processed and complete
     * their fragments against their core views of the api ([coreView]), which hold [main]'s classes alone, so the
     * build has no cycle: api main → domain compile and KSP → fragment → contract → generated code → api classes and jar.
     */
    private fun contractSources(api: Project, main: SourceSet, assemble: TaskProvider<AssembleContract>) {
        val sourceSets = api.extensions.getByType(SourceSetContainer::class.java)
        val generated = sourceSets.create(CONTRACT_SOURCE_SET)
        val tools = toolsConfiguration(api)
        val generate = api.tasks.register(GENERATE_CONTRACT_SOURCES_TASK, GenerateContractSources::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Generates the constants, refs and query fields of this api module's contexts from " +
                "its contract."
            task.contract.from(assemble.map { it.outputDirectory.file(PeerContracts.RESOURCE) })
            task.classpath.from(api.provider { generated.compileClasspath })
            task.tools.from(tools)
            task.outputDirectory.set(api.layout.buildDirectory.dir("generated/wow/contract/kotlin"))
        }
        (generated.extensions.getByName("kotlin") as SourceDirectorySet).srcDir(generate)
        listOf(
            generated.implementationConfigurationName to main.implementationConfigurationName,
            generated.compileOnlyConfigurationName to main.compileOnlyConfigurationName,
        ).forEach { (to, from) -> api.configurations.named(to) { it.extendsFrom(api.configurations.getByName(from)) } }
        generated.compileClasspath = api.files(main.output.classesDirs, generated.compileClasspath)
        generated.runtimeClasspath = api.files(main.output, generated.output, generated.runtimeClasspath)
        listOf(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME).forEach {
            api.configurations.named(it) { elements ->
                elements.outgoing.variants.named(CLASSES_VARIANT) { classes ->
                    classesArtifacts(classes, generated.output.classesDirs)
                }
            }
        }
        api.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java) { it.from(generated.output) }
    }

    /**
     * The core view of [api] for the domain module [domain]: a variant of its `apiElements` and of its
     * `runtimeElements` holding its main classes without the generated contract code, whose [API_VIEW] is
     * [coreView] of [domain]. Only [domain] requests it ([coreRequest]); every other consumer, an in-build peer
     * included, lacks the attribute's request, so Gradle picks the `classes` variant, which has fewer attributes.
     */
    private fun coreView(api: Project, domain: String) {
        api.dependencies.attributesSchema.attribute(API_VIEW)
        val main = api.extensions.getByType(SourceSetContainer::class.java).getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        listOf(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME).forEach {
            api.configurations.named(it) { elements ->
                elements.outgoing.variants.create(CORE_VARIANT + elements.outgoing.variants.size) { core ->
                    core.attributes { attributes ->
                        attributes.attribute(
                            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            api.objects.named(LibraryElements::class.java, LibraryElements.CLASSES),
                        )
                        attributes.attribute(API_VIEW, coreView(domain))
                    }
                    classesArtifacts(core, main.output.classesDirs)
                }
            }
        }
    }

    /**
     * Runs [action] once [project] is evaluated: the Kotlin plugin is applied by then, its source set extensions and
     * class directories in place, which they are not yet while it applies the `java` plugin.
     */
    private fun evaluated(project: Project, action: () -> Unit) {
        if (project.state.executed) {
            action()
        } else {
            project.afterEvaluate { action() }
        }
    }

    private fun classesArtifacts(variant: ConfigurationVariant, classesDirs: FileCollection) {
        classesDirs.files.forEach { directory ->
            variant.artifact(directory) { artifact ->
                artifact.type = ArtifactTypeDefinition.JVM_CLASS_DIRECTORY
                artifact.builtBy(classesDirs)
            }
        }
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

    /** What the Manifest [merge] writes gives: the [MANIFEST_ELEMENTS] variant, and [OPENAPI_TASK]'s document. */
    private fun mergedOutputs(project: Project, merge: TaskProvider<MergeManifest>) {
        manifestElements(project, merge)
        project.tasks.register(OPENAPI_TASK, GenerateOpenApi::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Writes the OpenAPI document of the service's routes from its manifest.json."
            task.manifestFile.set(merge.flatMap { it.outputFile })
            task.outputFile.set(project.layout.buildDirectory.file("wow/openapi.json"))
        }
    }

    /** Applies KSP with the model processor alone, and adds `wow-compiler` unless turned off. */
    private fun configureKsp(project: Project, extension: WowExtension) {
        project.pluginManager.apply(KSP)
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg(MODEL_OPTION, "generate")
            ksp.arg(MODULE_OPTION, project.name)
            ksp.arg(ContextArgument(extension.context))
            // A module is built by the model processor alone; the 9.x processors serve wow-core.
            NINE_X_PROCESSORS.forEach(ksp::excludeProcessor)
        }
        project.configurations.named(KSP_CONFIGURATION) { configuration ->
            configuration.withDependencies { dependencies ->
                if (extension.addCompilerDependency.get()) {
                    dependencies.add(project.dependencies.create("$GROUP:wow-compiler:$version"))
                }
            }
        }
    }

    /**
     * The Manifest compatibility gate: [CHECK_COMPATIBILITY_TASK] compares the merged Manifest with the
     * committed [WowExtension.manifestBaseline] and is part of `check`; [UPDATE_BASELINE_TASK] rewrites the baseline.
     */
    private fun compatibility(
        project: Project,
        extension: WowExtension,
        merge: TaskProvider<MergeManifest>,
        tools: Configuration,
    ) {
        val update = project.tasks.register(UPDATE_BASELINE_TASK, UpdateManifestBaseline::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Records the merged Manifest as the baseline checkManifestCompatibility compares with."
            task.manifest.set(merge.flatMap { it.outputFile })
            task.baseline.set(extension.manifestBaseline)
        }
        val check = project.tasks.register(CHECK_COMPATIBILITY_TASK, CheckManifestCompatibility::class.java) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description = "Fails on a change of the merged Manifest that breaks its baseline within the major version."
            task.baseline.set(extension.manifestBaseline)
            task.manifest.set(merge.flatMap { it.outputFile })
            task.tools.from(tools)
            // Recording the baseline and checking against it in one build checks the new baseline.
            task.mustRunAfter(update)
            task.report.set(project.layout.buildDirectory.file("wow/manifest-compatibility.txt"))
        }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { it.dependsOn(check) }
    }

    /**
     * [MANIFEST_ELEMENTS]: the outgoing variant of the service's merged `manifest.json`, for tools that read a
     * service's Manifest. Its attributes are those of a documentation variant (like the sources and Javadoc ones Gradle
     * defines) of the docs type [MANIFEST_DOCS_TYPE], so no other variant matches a request for it. It is added to the `java` component as an optional variant: a publication carries the Manifest
     * as the artifact of classifier [MANIFEST_CLASSIFIER] (`<name>-<version>-wow-manifest.json`), and Gradle Module
     * Metadata lets a consumer select it by the same attributes.
     */
    private fun manifestElements(project: Project, merge: TaskProvider<MergeManifest>) {
        val elements = project.configurations.create(MANIFEST_ELEMENTS) { configuration ->
            configuration.description = "The service's merged Wow Manifest, for the services that take it as a peer."
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            configuration.isVisible = false
            documentationAttributes(project, configuration, MANIFEST_DOCS_TYPE)
        }
        project.artifacts.add(elements.name, merge.flatMap { it.outputFile }) { artifact ->
            artifact.builtBy(merge)
            artifact.type = MANIFEST_EXTENSION
            artifact.extension = MANIFEST_EXTENSION
            artifact.classifier = MANIFEST_CLASSIFIER
        }
        project.pluginManager.withPlugin("java") {
            val component = project.components.getByName("java") as AdhocComponentWithVariants
            component.addVariantsFromConfiguration(elements) { it.mapToOptional() }
        }
    }

    /** The attributes of a documentation variant of [docsType]: the merged Manifest's, or the peer contract's. */
    private fun documentationAttributes(project: Project, configuration: Configuration, docsType: String) {
        val objects = project.objects
        configuration.attributes { attributes ->
            attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                objects.named(Category::class.java, Category.DOCUMENTATION)
            )
            attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType::class.java, docsType))
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        }
    }

    /**
     * [TOOLS_CONFIGURATION]: the introspection tools the workers load (victools, Jackson, the kernel), resolved in
     * the build's project so they stay off the build script's class path. By default it holds
     * `me.ahoo.wow:wow-manifest-tools` of the plugin's version; declaring a dependency in it replaces that default.
     */
    private fun toolsConfiguration(project: Project): Configuration {
        project.configurations.findByName(TOOLS_CONFIGURATION)?.let { return it }
        val objects = project.objects
        return project.configurations.create(TOOLS_CONFIGURATION) { configuration ->
            configuration.description = "The tools the Wow Manifest tasks run in their isolated workers."
            configuration.isCanBeConsumed = false
            configuration.isVisible = false
            configuration.attributes { attributes ->
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
                attributes.attribute(
                    Bundling.BUNDLING_ATTRIBUTE,
                    objects.named(Bundling::class.java, Bundling.EXTERNAL)
                )
            }
            configuration.defaultDependencies { dependencies ->
                dependencies.add(project.dependencies.create("$GROUP:wow-manifest-tools:$version"))
            }
        }
    }

    /** `wow.context`, only when [WowExtension.context] is set: otherwise the compiler takes the `@BoundedContext`. */
    internal class ContextArgument(
        @get:Input @get:Optional val context: Provider<String>
    ) : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOfNotNull(context.orNull?.let { "$CONTEXT_OPTION=$it" })
    }

    companion object {
        const val EXTENSION = "wow"
        const val GENERATE_FRAGMENT_TASK = "generateWowManifestFragment"
        const val MERGE_TASK = "mergeWowManifest"
        const val OPENAPI_TASK = "generateWowOpenApi"
        const val TOOLS_CONFIGURATION = "wowManifestTools"
        const val GENERATE_CONTRACT_TASK = "generateWowContract"
        const val CONTRACT_ELEMENTS = "wowContractElements"
        const val ASSEMBLE_CONTRACT_TASK = "assembleWowContract"
        const val CONTRACT_CONFIGURATION = "wowContract"
        const val MANIFEST_ELEMENTS = "wowManifestElements"
        const val GENERATE_CONTRACT_SOURCES_TASK = "generateWowContractSources"
        const val CONTRACT_SOURCE_SET = "wowContract"

        /**
         * The attribute of an api module's core views: [coreView] of the domain module that requests it, whose
         * contract the api's generated code comes from.
         */
        val API_VIEW: Attribute<String> = Attribute.of("me.ahoo.wow.api-view", String::class.java)

        /** The [API_VIEW] value of the core view of an api module for the domain module [domain] (its path). */
        fun coreView(domain: String): String = "core$domain"
        const val CHECK_COMPATIBILITY_TASK = "checkManifestCompatibility"
        const val UPDATE_BASELINE_TASK = "updateManifestBaseline"

        /** Where the Manifest baseline lives in the project by default. */
        const val BASELINE_FILE = "manifest/baseline.json"

        /** The docs type of the merged Manifest's variant, and the classifier of its published artifact. */
        const val MANIFEST_DOCS_TYPE = "wow-manifest"
        const val MANIFEST_CLASSIFIER = "wow-manifest"

        /** The docs type of the peer contract's variant. */
        const val CONTRACT_DOCS_TYPE = "wow-contract"
        private const val API_CONFIGURATION = "api"
        private const val CLASSES_VARIANT = "classes"
        private const val CORE_VARIANT = "wowCore"
        private const val MANIFEST_EXTENSION = "json"
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

        /** Why a module that applies the plugin without the Kotlin JVM plugin fails ([requireKotlinJvm]). */
        internal fun missingKotlinJvm(path: String): String =
            "Project '$path' applies the Wow plugin (me.ahoo.wow) without the Kotlin JVM plugin " +
                "(org.jetbrains.kotlin.jvm), so it would compile no Wow models: the Wow compiler runs as a KSP " +
                "processor of the Kotlin compilation. Apply kotlin(\"jvm\") in the module as well; a module of " +
                "Java sources only needs it too, and its Java sources are then processed."

        /** The plugin's version, which is also the version of `wow-compiler` it adds. */
        val version: String by lazy {
            Properties().apply {
                WowPlugin::class.java.getResourceAsStream("/META-INF/wow-gradle-plugin.properties")!!.use(::load)
            }.getProperty("version")
        }
    }
}
