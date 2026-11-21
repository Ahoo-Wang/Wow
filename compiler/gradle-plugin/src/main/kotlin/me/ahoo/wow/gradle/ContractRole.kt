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
import me.ahoo.wow.gradle.WowArtifacts.toolsConfiguration
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACTS_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_JAR_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_SOURCE_SET
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_CONTRACT_SOURCES_TASK
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.ivy.IvyPublication
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar

/**
 * The contract of an api module, apart from its types: `META-INF/wow/contract.json` and the code generated from it,
 * `<Context>Contract` and `<State>View`, in a jar of their own ([CONTRACT_JAR_TASK]). Every name and value in them comes
 * from compiling the domain modules the api declares, so the api's own variants, which every module compiles against,
 * hold its types alone, and no build waits for a domain to reach another context's types.
 *
 * The contract is the api module's feature [FEATURE], capability `<group>:<name>-contract` (by its project's name
 * and by each module name it is published under, [publishedCapabilities]), published within the
 * module as Gradle publishes test fixtures: the variants [CONTRACT_API_ELEMENTS] and [CONTRACT_RUNTIME_ELEMENTS], the
 * jar of classifier `contract`, and its sources' jar when the module publishes sources. Its own variants carry
 * [WowPlugin.CONTRACT_ATTRIBUTE], naming the feature, by which a service finds every api's contract
 * ([ContractDerivation]); `-P`[PUBLISH_PROPERTY]`=false` publishes the types alone, a cycle's first release.
 *
 * A module that uses an api's generated code asks for it, `implementation(wow.contract(project(":ordering-api")))`
 * ([WowExtension.contract]); the contract's variants depend on the api's types.
 */
internal object ContractRole {
    /** The feature of an api module's contract, whose capability is `<group>:<name>-contract`. */
    const val FEATURE = "contract"

    /**
     * The declarable configuration of the contract variants' dependencies, the api's own types: the generated source
     * set's `api` configuration ([CONTRACT_SOURCE_SET]).
     */
    const val CONTRACT_API = "wowContractApi"

    /** The contract variants, of capability `<group>:<name>-contract`. */
    const val CONTRACT_API_ELEMENTS = "wowContractApiElements"
    const val CONTRACT_RUNTIME_ELEMENTS = "wowContractRuntimeElements"

    /** The contract's sources, a variant of the same capability, when the module publishes its own. */
    const val CONTRACT_SOURCES_ELEMENTS = "wowContractSourcesElements"

    /**
     * The Gradle property that, `false`, publishes the api module's types alone, without its contract: the first
     * release of a member of a cycle of contexts that consume each other across repositories, whose domain cannot yet
     * compile against its peer's types.
     */
    const val PUBLISH_PROPERTY = "wow.contract.publish"

    /**
     * The contract of [api], which declares domain modules: [CONTRACT_JAR_TASK] packs what [assemble] keeps and, with
     * the Kotlin JVM plugin ([kotlin]), the code generated from it ([contractSources]); the contract variants offer the
     * jar, and the api's component publishes them ([publish]).
     */
    fun contract(api: Project, main: SourceSet, assemble: TaskProvider<AssembleContract>, kotlin: Boolean) {
        val jar = api.tasks.register(CONTRACT_JAR_TASK, Jar::class.java) { jar ->
            jar.description = "Assembles this api module's contract and the code generated from it."
            jar.from(assemble)
            jar.archiveClassifier.set(FEATURE)
        }
        val generated = if (kotlin) contractSources(api, main, assemble) else null
        generated?.let { sources -> jar.configure { it.from(sources.output) } }
        // The generated source set's own, which the Kotlin plugin creates: its code compiles against the api's types.
        val dependencies = api.configurations.findByName(CONTRACT_API)
            ?: api.configurations.dependencyScope(CONTRACT_API) { configuration ->
                configuration.description = "The dependencies of this api module's contract: its own types."
            }.get()
        dependencies.dependencies.add(api.dependencies.project(mapOf("path" to api.path)))
        val capability = "${api.group}:${api.name}-$FEATURE:${api.version}"
        listOf(CONTRACT_API_ELEMENTS to Usage.JAVA_API, CONTRACT_RUNTIME_ELEMENTS to Usage.JAVA_RUNTIME)
            .forEach { (name, usage) ->
                api.configurations.consumable(name) { elements ->
                    elements.description = "This api module's contract ($usage), of capability $capability."
                    elements.extendsFrom(dependencies)
                    jvmAttributes(api, elements, usage)
                    elements.outgoing.capability(capability)
                    publishedCapabilities(api, elements)
                    elements.outgoing.artifact(jar) { it.type = ArtifactTypeDefinition.JAR_TYPE }
                }
            }
        publish(api, capability, generated)
    }

    /**
     * Publishes [api]'s contract within its module, unless [PUBLISH_PROPERTY] is `false`: its own variants carry
     * [WowPlugin.CONTRACT_ATTRIBUTE], and the `java` component the contract variants, mapped to Maven's optional
     * dependencies, and, when the module publishes its sources, those of the [generated] code
     * ([CONTRACT_SOURCES_ELEMENTS]).
     */
    private fun publish(api: Project, capability: String, generated: SourceSet?) {
        if (typesOnly(api)) {
            api.logger.lifecycle(
                "Publishing ${api.path} ${api.version} without its contract (-P$PUBLISH_PROPERTY=false): a " +
                    "types-only release, the first of a cycle, under a version of its own; services need the " +
                    "module's full release.",
            )
            return
        }
        listOf(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME).forEach {
            api.configurations.named(it) { own -> own.attributes.attribute(WowPlugin.CONTRACT_ATTRIBUTE, FEATURE) }
        }
        val component = api.components.getByName("java") as AdhocComponentWithVariants
        listOf(CONTRACT_API_ELEMENTS to "compile", CONTRACT_RUNTIME_ELEMENTS to "runtime").forEach { (name, scope) ->
            component.addVariantsFromConfiguration(api.configurations.getByName(name)) {
                it.mapToMavenScope(scope)
                it.mapToOptional()
            }
        }
        val sources = api.configurations.findByName(JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME)
        if (generated != null && sources != null) {
            val jar = api.tasks.register(generated.sourcesJarTaskName, Jar::class.java) { jar ->
                jar.description = "Assembles the sources of the code generated from this api module's contract."
                jar.from(generated.allSource)
                jar.archiveClassifier.set("$FEATURE-sources")
            }
            val elements = api.configurations.consumable(CONTRACT_SOURCES_ELEMENTS) { elements ->
                elements.description = "The sources of this api module's contract code, of capability $capability."
                documentationAttributes(api, elements, DocsType.SOURCES)
                elements.outgoing.capability(capability)
                publishedCapabilities(api, elements)
                elements.outgoing.artifact(jar)
            }
            component.addVariantsFromConfiguration(elements.get()) { it.mapToOptional() }
        }
    }

    /**
     * Offers on [elements] the contract's capability under each module name [api] is published by, beside its
     * project's name: a consumer by coordinates, `wow.contract("<group>:<artifactId>:<version>")` or a service deriving
     * the contract, asks the published module for its feature, `<group>:<artifactId>-contract`, while one in the build
     * asks the project, `<group>:<project name>-contract`. So `:ops-api` published as `wow-ops-api` offers both. Each
     * Maven publication's `artifactId` and each Ivy publication's `module` is read when the variant is, so a build
     * that sets it after the plugin is applied is heard; a name equal to the project's offers no other capability.
     */
    private fun publishedCapabilities(api: Project, elements: Configuration) {
        fun offer(name: () -> String) =
            elements.outgoing.capability(api.provider { "${api.group}:${name()}-$FEATURE:${api.version}" })
        api.plugins.withId("maven-publish") {
            api.extensions.getByType(PublishingExtension::class.java).publications
                .withType(MavenPublication::class.java).all { publication -> offer { publication.artifactId } }
        }
        api.plugins.withId("ivy-publish") {
            api.extensions.getByType(PublishingExtension::class.java).publications
                .withType(IvyPublication::class.java).all { publication -> offer { publication.module } }
        }
    }

    /** Whether [project]'s build publishes api modules without their contracts ([PUBLISH_PROPERTY] `false`). */
    fun typesOnly(project: Project): Boolean = project.providers.gradleProperty(PUBLISH_PROPERTY).orNull == "false"

    /** Why a service of a build that publishes api modules without their contracts fails to merge its Manifest. */
    fun typesOnlyService(path: String): String =
        "Project '$path' merges its Manifest in a build run with -P$PUBLISH_PROPERTY=false, which leaves the api " +
            "modules without their contracts: that property is for publishing a cycle member's types alone. Build " +
            "the service without it."

    private fun jvmAttributes(project: Project, configuration: Configuration, usage: String) {
        val objects = project.objects
        configuration.attributes { attributes ->
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, usage))
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
            attributes.attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class.java, LibraryElements.JAR),
            )
        }
    }

    /**
     * The code generated from [api]'s contract: [GENERATE_CONTRACT_SOURCES_TASK] ([GenerateContractSources]) writes it
     * from what [assemble] keeps, and the source set [CONTRACT_SOURCE_SET] compiles it against [main]'s classes and
     * dependencies, for the contract's jar. The domain modules, whose contracts it comes from, compile, are processed
     * and complete their fragments against the api's types, so the build has no cycle: api types → domain compile and
     * KSP → fragment → contract → generated code → contract jar.
     */
    private fun contractSources(api: Project, main: SourceSet, assemble: TaskProvider<AssembleContract>): SourceSet {
        val sourceSets = api.extensions.getByType(SourceSetContainer::class.java)
        val generated = sourceSets.create(CONTRACT_SOURCE_SET)
        val tools = toolsConfiguration(api)
        val generate = api.tasks.register(GENERATE_CONTRACT_SOURCES_TASK, GenerateContractSources::class.java) { task ->
            task.description = "Generates the constants, refs, query fields and state views of this api module's " +
                "contexts from its contract."
            task.contract.from(assemble.map { it.outputDirectory.file(ApiRole.CONTRACT_RESOURCE) })
            val contracts = api.configurations.getByName(CONTRACTS_CONFIGURATION)
            task.contractPackages.from(ApiRole.contractPackagesOf(contracts))
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
        return generated
    }
}
