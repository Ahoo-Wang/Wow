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
import me.ahoo.wow.gradle.WowPlugin.Companion.API_VIEW
import me.ahoo.wow.gradle.WowPlugin.Companion.ASSEMBLE_CONTRACT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.BASELINE_FILE
import me.ahoo.wow.gradle.WowPlugin.Companion.CHECK_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACTS_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_DOCS_TYPE
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_ELEMENTS
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_SOURCE_SET
import me.ahoo.wow.gradle.WowPlugin.Companion.CORE_JAR_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_CONTRACT_SOURCES_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_CONTRACT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_FRAGMENT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.INTEGRATION_TEST
import me.ahoo.wow.gradle.WowPlugin.Companion.MANIFEST_CLASSIFIER
import me.ahoo.wow.gradle.WowPlugin.Companion.MANIFEST_DOCS_TYPE
import me.ahoo.wow.gradle.WowPlugin.Companion.MANIFEST_ELEMENTS
import me.ahoo.wow.gradle.WowPlugin.Companion.MERGE_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.OPENAPI_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.TOOLS_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.UPDATE_BASELINE_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.coreView
import me.ahoo.wow.gradle.WowPlugin.Companion.missingKotlinJvm
import me.ahoo.wow.gradle.WowPlugin.Companion.moduleName
import me.ahoo.wow.gradle.WowPlugin.Companion.requireKotlin
import me.ahoo.wow.gradle.WowPlugin.Companion.version
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationVariant
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
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
import org.gradle.api.plugins.jvm.JvmTestSuite
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
import org.gradle.testing.base.TestingExtension

/**
 * The roles of the plugins, each configuring the one project it is applied to: the domain role
 * ([domain]: `me.ahoo.wow`, and `me.ahoo.wow.service` on its own sources), the api role ([api]: `me.ahoo.wow.api`)
 * and the service role ([service]: `me.ahoo.wow.service`). The api module pairs with its domain modules by declaring
 * them ([WowPlugin.CONTRACT_CONFIGURATION]); no role reads or configures another project, so the build may use
 * Gradle's isolated projects.
 */
internal object WowRoles {
    /**
     * The domain role of [project], applied by the plugin [id]: once the Kotlin JVM plugin is applied, the Wow
     * dependencies, KSP, the fragment, the contract and the integration test suite. Without the Kotlin JVM plugin by
     * the end of its evaluation the project fails, naming the fix.
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
        wowDependencies(project, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, KERNEL, KERNEL_PROJECT)
        configureKsp(project, extension)
        configureIntegrationTest(project)

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
            .matching { it.include("${Manifest.FRAGMENT_DIRECTORY}/*.json") }
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
            it.exclude("${Manifest.FRAGMENT_DIRECTORY}/**")
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
            task.fragments.from(fragments.matching { it.include("${Manifest.FRAGMENT_DIRECTORY}/*.json") })
            task.service.set(extension.service)
            task.module.set(project.path)
            task.unpaired.set(unpaired)
            task.unpairedProjects.set(unpaired.map { it.values.distinct().sorted() })
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
     * `wow-compiler` itself (a build that pins it, or Wow's own build).
     */
    private fun configureKsp(project: Project, extension: WowExtension) {
        project.pluginManager.apply(KSP)
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg(MODEL_OPTION, "generate")
            ksp.arg(MODULE_OPTION, moduleName(project.path, project.rootProject.name))
            ksp.arg(ContextArgument(extension.context))
        }
        project.configurations.named(KSP_CONFIGURATION) { configuration ->
            configuration.withDependencies { dependencies ->
                if (dependencies.none { declares(it, COMPILER, COMPILER_PROJECT) }) {
                    dependencies.add(project.dependencies.create("$GROUP:$COMPILER:$version"))
                }
            }
        }
    }

    /**
     * Adds `platform("me.ahoo.wow:wow-bom:<plugin version>")` to [configuration] of [project], so its Wow dependencies
     * take no version, and the role's Wow module [artifact] unless the module declares it in a configuration it
     * compiles against ([DECLARING]): by its coordinates, as Wow's own build's project [ownProject], or as its jar among
     * files.
     */
    private fun wowDependencies(project: Project, configuration: String, artifact: String, ownProject: String) {
        project.configurations.named(configuration) {
            it.withDependencies { dependencies ->
                val declared = DECLARING.mapNotNull(project.configurations::findByName)
                    .filter { other -> other.name != configuration }.flatMap { other -> other.dependencies } +
                    dependencies
                if (declared.none { dependency -> declares(dependency, artifact, ownProject) }) {
                    dependencies.add(project.dependencies.create("$GROUP:$artifact"))
                }
                dependencies.add(project.dependencies.platform("$GROUP:$BOM:$version"))
            }
        }
    }

    /** Whether [dependency] declares the Wow module [artifact]: by its coordinates, as [ownProject], or as its jar. */
    private fun declares(dependency: Dependency, artifact: String, ownProject: String): Boolean = when (dependency) {
        is FileCollectionDependency -> dependency.files.any { it.name.startsWith("$artifact-") }
        is ProjectDependency -> dependency.group == GROUP && dependency.name == ownProject
        else -> dependency.name == artifact
    }

    /**
     * The test suite [INTEGRATION_TEST], unless the build declares one: JUnit Jupiter,
     * against the module's classes and everything `testImplementation` gives (the test DSL, Testcontainers), for the
     * tests that run on containers. It is not part of `check`: `./gradlew integrationTest` runs it.
     */
    private fun configureIntegrationTest(project: Project) {
        val suites = project.extensions.getByType(TestingExtension::class.java).suites
        if (suites.findByName(INTEGRATION_TEST) != null) {
            return
        }
        suites.register(INTEGRATION_TEST, JvmTestSuite::class.java) { suite ->
            suite.useJUnitJupiter()
            suite.dependencies { dependencies -> dependencies.implementation.add(dependencies.project()) }
            suite.targets.configureEach { target ->
                target.testTask.configure { task ->
                    task.description = "Runs the container-backed integration tests."
                    task.shouldRunAfter(project.tasks.named(JavaPlugin.TEST_TASK_NAME))
                }
            }
        }
        project.configurations.named("${INTEGRATION_TEST}Implementation") {
            it.extendsFrom(project.configurations.getByName(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME))
        }
        project.configurations.named("${INTEGRATION_TEST}RuntimeOnly") {
            it.extendsFrom(project.configurations.getByName(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME))
        }
    }

    /**
     * The api role of [project]: a `java-library` with the Wow dependencies, the declarable [CONTRACT_CONFIGURATION]
     * of its domain modules and the resolvable [CONTRACTS_CONFIGURATION] of their contracts, the contract it assembles
     * into its jar, a core view per declared domain module, and, with the Kotlin JVM plugin, the code generated from
     * its contract.
     */
    fun api(project: Project) {
        project.pluginManager.apply(JavaLibraryPlugin::class.java)
        wowDependencies(project, JavaPlugin.API_CONFIGURATION_NAME, API, API_PROJECT)
        // Created with the plugin, so the build script declares in it: the contract code generator runs over it.
        toolsConfiguration(project)
        val declared = project.configurations.create(CONTRACT_CONFIGURATION) { configuration ->
            configuration.description = "The domain modules whose contracts this api module carries."
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = false
        }
        val contracts = project.configurations.create(CONTRACTS_CONFIGURATION) { configuration ->
            configuration.description = "The contracts of the domain modules wowContract declares."
            configuration.extendsFrom(declared)
            configuration.isCanBeConsumed = false
            configuration.isTransitive = false
            configuration.isVisible = false
            documentationAttributes(project, configuration, CONTRACT_DOCS_TYPE)
        }
        val main = project.extensions.getByType(SourceSetContainer::class.java)
            .getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val assemble = project.tasks.register(ASSEMBLE_CONTRACT_TASK, AssembleContract::class.java) { task ->
            task.description = "Takes the contracts of this api module's contexts from the domain modules it declares."
            task.contracts.from(contracts)
            task.owners.set(ownersOf(contracts))
            task.classes.from(main.output.classesDirs)
            task.owner.set(project.path)
            task.outputDirectory.set(project.layout.buildDirectory.dir("wow/contract"))
        }
        project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java) { it.from(assemble) }
        project.dependencies.attributesSchema.attribute(API_VIEW)
        declared.dependencies.withType(ProjectDependency::class.java).configureEach { domain ->
            coreView(project, main, domain.path)
        }
        project.pluginManager.withPlugin(KOTLIN_JVM) { evaluated(project) { contractSources(project, main, assemble) } }
    }

    /**
     * The code generated from [api]'s contract: [GENERATE_CONTRACT_SOURCES_TASK]
     * ([GenerateContractSources]) writes it from what [assemble] keeps, and the source set [CONTRACT_SOURCE_SET]
     * compiles it against [main]'s classes and dependencies. Its classes join the api's classes variants and jar, so
     * every consumer sees them, and its sources the sources jar. The domain modules, whose contracts they come from,
     * compile, are processed and complete their fragments against their core views of the api ([coreView]), jars of
     * [main]'s output alone, so the build has no cycle under either compile class path packaging: api main → domain
     * compile and KSP → fragment → contract → generated code → api classes and jar.
     */
    private fun contractSources(api: Project, main: SourceSet, assemble: TaskProvider<AssembleContract>) {
        val sourceSets = api.extensions.getByType(SourceSetContainer::class.java)
        val generated = sourceSets.create(CONTRACT_SOURCE_SET)
        val tools = toolsConfiguration(api)
        val generate = api.tasks.register(GENERATE_CONTRACT_SOURCES_TASK, GenerateContractSources::class.java) { task ->
            task.description = "Generates the constants, refs, query fields and state views of this api module's " +
                "contexts from its contract."
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
        // The sources jar, when the module publishes one (`java { withSourcesJar() }`), holds the generated sources.
        api.tasks.withType(Jar::class.java).matching { it.name == main.sourcesJarTaskName }.configureEach {
            it.from(generate)
        }
    }

    /**
     * The core view of [api] for the domain module [domain]: a variant of its `apiElements` and of its
     * `runtimeElements`, named after [domain]'s path, holding [CORE_JAR_TASK]'s jar, its main classes and resources
     * without the generated contract code or the assembled contract, whose [API_VIEW] is [coreView] of [domain]. Only
     * [domain] requests it ([coreRequest]); every other consumer lacks the attribute's request, so Gradle picks the
     * api's own variants, which have fewer attributes.
     *
     * A jar answers a request for classes too, and the variant matches the domain's request for the view, which none of
     * the api's own variants does, so Gradle picks it whether the domain's compile class path resolves to classes or to
     * jars (`org.gradle.java.compile-classpath-packaging`). Two core variants, classes and jar, would match a request for
     * classes alike and leave it ambiguous. The variant is not published ([unpublishedCore]): the api's POM and module
     * metadata stay its one jar, contract included.
     */
    private fun coreView(api: Project, main: SourceSet, domain: String) {
        val jar = coreJar(api, main)
        unpublishedCore(api)
        listOf(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME).forEach {
            api.configurations.named(it) { elements ->
                elements.outgoing.variants.create(coreVariant(domain)) { core ->
                    core.attributes { attributes ->
                        attributes.attribute(
                            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            api.objects.named(LibraryElements::class.java, LibraryElements.JAR),
                        )
                        attributes.attribute(API_VIEW, coreView(domain))
                    }
                    core.artifact(jar) { artifact -> artifact.type = ArtifactTypeDefinition.JAR_TYPE }
                }
            }
        }
    }

    /** The name of the core variant for the domain module at [path]: `:eshop:ordering-domain` is `wowCoreEshopOrderingDomain`. */
    internal fun coreVariant(path: String): String = CORE_VARIANT + path.split(':', '-', '_', '.')
        .filter(String::isNotEmpty).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    /**
     * Keeps [api]'s core views out of its publication: a secondary variant with a jar would otherwise be published
     * beside the api's own, and an external consumer has no domain module to request it.
     */
    private fun unpublishedCore(api: Project) {
        if (api.extensions.extraProperties.has(CORE_UNPUBLISHED)) return
        api.extensions.extraProperties.set(CORE_UNPUBLISHED, true)
        val component = api.components.getByName("java") as AdhocComponentWithVariants
        listOf(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME).forEach {
            component.withVariantsFromConfiguration(api.configurations.getByName(it)) { details ->
                if (details.configurationVariant.name.startsWith(CORE_VARIANT)) details.skip()
            }
        }
    }

    /**
     * [CORE_JAR_TASK]: the jar of [api]'s core view, its [main] classes and resources alone, without what the `jar`
     * task adds from the contract; one per api module, whatever domain modules pair with it.
     */
    private fun coreJar(api: Project, main: SourceSet): TaskProvider<Jar> =
        if (CORE_JAR_TASK in api.tasks.names) {
            api.tasks.named(CORE_JAR_TASK, Jar::class.java)
        } else {
            api.tasks.register(CORE_JAR_TASK, Jar::class.java) { jar ->
                jar.description = "Assembles this api module's classes without its contract, for its paired domains."
                jar.from(main.output)
                jar.archiveClassifier.set(CORE_CLASSIFIER)
                jar.destinationDirectory.set(api.layout.buildDirectory.dir("wow/core"))
            }
        }

    /**
     * The service role of [project]: the service's Manifest merged from the fragments of its runtime class path, its
     * OpenAPI document, its outgoing variant, and the compatibility gate.
     */
    fun service(project: Project, extension: WowServiceExtension) {
        extension.manifestBaseline.convention(project.layout.projectDirectory.file(BASELINE_FILE))
        project.pluginManager.withPlugin(KOTLIN_JVM) { configureService(project, extension) }
    }

    private fun configureService(project: Project, extension: WowServiceExtension) {
        val tools = toolsConfiguration(project)
        val main = project.extensions.getByType(
            SourceSetContainer::class.java
        ).getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val runtimeClasspath = project.configurations.named(main.runtimeClasspathConfigurationName)
        val generate = project.tasks.named(GENERATE_FRAGMENT_TASK)
        val fragmentDirectory = project.layout.buildDirectory.dir(FRAGMENT_DIRECTORY)
        val merge = project.tasks.register(MERGE_TASK, MergeManifest::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Merges the Manifest fragments on the runtime class path into the service's manifest.json."
            // The module's own completed fragment, none when it has no model or processor, and its dependencies'.
            task.classpath.from(project.files(fragmentDirectory).builtBy(generate), runtimeClasspath)
            val owner = "project '${project.path}'"
            task.owners.putAll(fragmentDirectory.map { mapOf(it.asFile.path to owner) })
            task.owners.putAll(runtimeClasspath.flatMap(::ownersOf))
            task.tools.from(tools)
            // The module's own contexts name their service as the contracts on the class path do.
            task.serviceName.set(extension.service)
            task.defaultServiceName.set(moduleName(project.path, project.rootProject.name))
            task.serviceVersion.set(project.provider { project.version.toString() })
            task.outputFile.set(project.layout.buildDirectory.file("wow/manifest.json"))
        }
        manifestElements(project, merge)
        project.tasks.register(OPENAPI_TASK, GenerateOpenApi::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Writes the OpenAPI document of the service's routes from its manifest.json."
            task.manifestFile.set(merge.flatMap { it.outputFile })
            task.outputFile.set(project.layout.buildDirectory.file("wow/openapi.json"))
        }
        compatibility(project, extension, merge, tools)
    }

    /**
     * The Manifest compatibility gate: [CHECK_TASK] compares the merged Manifest with the committed
     * [WowServiceExtension.manifestBaseline] and is part of `check`; [UPDATE_BASELINE_TASK] rewrites the baseline.
     */
    private fun compatibility(
        project: Project,
        extension: WowServiceExtension,
        merge: TaskProvider<MergeManifest>,
        tools: Configuration,
    ) {
        val update = project.tasks.register(UPDATE_BASELINE_TASK, UpdateManifestBaseline::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Records the merged Manifest as the baseline $CHECK_TASK compares with."
            task.manifest.set(merge.flatMap { it.outputFile })
            task.baseline.set(extension.manifestBaseline)
        }
        val check = project.tasks.register(CHECK_TASK, CheckManifestCompatibility::class.java) { task ->
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
     * defines) of the docs type [MANIFEST_DOCS_TYPE], so no other variant matches a request for it. It is added to the
     * `java` component as an optional variant: a publication carries the Manifest as the artifact of classifier
     * [MANIFEST_CLASSIFIER] (`<name>-<version>-wow-manifest.json`), and Gradle Module Metadata lets a consumer select
     * it by the same attributes.
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

    /**
     * The owner of each file [configuration] resolves to, by its path, as a failure names it: `project ':x'` for a
     * project of the build, the coordinates for a module. Only messages read it.
     */
    private fun ownersOf(configuration: Configuration): Provider<Map<String, String>> =
        configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.associate { artifact ->
                artifact.file.path to when (val id = artifact.id.componentIdentifier) {
                    is ProjectComponentIdentifier -> "project '${id.projectPath}'"
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                    else -> id.displayName
                }
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
            configuration.description = "The tools the Wow tasks run in their isolated workers."
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

    private const val GROUP_NAME = "wow"
    private const val GROUP = "me.ahoo.wow"
    private const val BOM = "wow-bom"
    private const val COMPILER = "wow-compiler"
    private const val COMPILER_PROJECT = "compiler"
    private const val KERNEL = "wow-kernel"
    private const val KERNEL_PROJECT = "kernel"
    private const val API = "wow-api"
    private const val API_PROJECT = "api"
    private const val CLASSES_VARIANT = "classes"

    /** The configurations a module declares what it compiles against in. */
    private val DECLARING = listOf(
        JavaPlugin.API_CONFIGURATION_NAME,
        JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
        JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
        JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME,
    )
    private const val CORE_VARIANT = "wowCore"
    private const val CORE_CLASSIFIER = "wow-core"
    private const val CORE_UNPUBLISHED = "wowCoreUnpublished"
    private const val MANIFEST_EXTENSION = "json"
    private const val FRAGMENT_DIRECTORY = "wow/manifest-fragment"
    private const val KOTLIN_JVM = "org.jetbrains.kotlin.jvm"
    private const val KOTLIN_WRAPPER = "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt"
    private const val KSP = "com.google.devtools.ksp"
    private const val KSP_CONFIGURATION = "ksp"
    private const val KSP_TASK = "kspKotlin"
    private const val KSP_RESOURCES = "generated/ksp/main/resources"
    private const val MODEL_OPTION = "wow.model"
    private const val MODULE_OPTION = "wow.manifest.module"
    private const val CONTEXT_OPTION = "wow.context"
}
