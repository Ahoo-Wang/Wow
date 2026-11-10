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

import me.ahoo.wow.gradle.WowArtifacts.documentationAttributes
import me.ahoo.wow.gradle.WowArtifacts.ownersOf
import me.ahoo.wow.gradle.WowArtifacts.toolsConfiguration
import me.ahoo.wow.gradle.WowArtifacts.wowDependencies
import me.ahoo.wow.gradle.WowPlugin.Companion.API_VIEW
import me.ahoo.wow.gradle.WowPlugin.Companion.ASSEMBLE_CONTRACT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACTS_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_DOCS_TYPE
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_SOURCE_SET
import me.ahoo.wow.gradle.WowPlugin.Companion.CORE_JAR_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_CONTRACT_SOURCES_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.KOTLIN_JVM
import me.ahoo.wow.gradle.WowPlugin.Companion.coreView
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationVariant
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar

/**
 * The api role of a project ([api]: `me.ahoo.wow.api`): a `java-library` that pairs with the domain modules it declares
 * ([WowPlugin.CONTRACT_CONFIGURATION]), carries their peer contract in its jar, offers each a core view of itself, and,
 * with the Kotlin JVM plugin, compiles the code generated from the contract.
 */
internal object ApiRole {
    /**
     * The api role of [project]: a `java-library` with the Wow dependencies, the declarable [CONTRACT_CONFIGURATION]
     * of its domain modules and the resolvable [CONTRACTS_CONFIGURATION] of their contracts, the contract it assembles
     * into its jar, a core view per declared domain module, and, with the Kotlin JVM plugin, the code generated from
     * its contract.
     */
    fun api(project: Project) {
        project.pluginManager.apply(JavaLibraryPlugin::class.java)
        wowDependencies(project, JavaPlugin.API_CONFIGURATION_NAME, API)
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
            task.tools.from(toolsConfiguration(project))
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
            task.contract.from(assemble.map { it.outputDirectory.file(CONTRACT_RESOURCE) })
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

    /** The core variant of the domain module at [path], one per path: `:eshop:ordering-domain` is `wowCore:eshop:ordering-domain`. */
    internal fun coreVariant(path: String): String = CORE_VARIANT + path

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

    private const val API = "wow-api"
    private const val CLASSES_VARIANT = "classes"
    private const val CORE_VARIANT = "wowCore"
    private const val CORE_CLASSIFIER = "wow-core"
    private const val CORE_UNPUBLISHED = "wowCoreUnpublished"

    /** The peer contract an api module's jar carries. */
    const val CONTRACT_RESOURCE = "META-INF/wow/contract.json"
}
