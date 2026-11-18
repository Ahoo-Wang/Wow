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

import me.ahoo.wow.gradle.WowArtifacts.toolsConfiguration
import me.ahoo.wow.gradle.WowPlugin.Companion.API_VIEW
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_JAR_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_SOURCE_SET
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_CONTRACT_SOURCES_TASK
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar

/**
 * The contract of an api module, apart from its types: `META-INF/wow/contract.json` and
 * the code generated from it, `<Context>Contract` and `<State>View`, in a jar of their own ([CONTRACT_JAR_TASK]). Every
 * name and value in them comes from compiling the domain modules the api declares, so the api's own variants, which
 * every module compiles against, hold its types alone, and no build waits for a domain to reach another context's
 * types.
 *
 * The contract is the api's feature [FEATURE], capability `<group>:<name>-contract`:
 *
 * - a module that uses an api's generated code asks for it, `implementation(wow.contract(project(":ordering-api")))`
 *   ([WowExtension.contract]); its variants depend on the api's types;
 * - a service, and the tests of a domain or a service, see every api's contract ([requestContracts]): they ask for the
 *   [CONTRACT_VIEW] of [API_VIEW], which the api's [VIEW_API_ELEMENTS] and [VIEW_RUNTIME_ELEMENTS] answer with its
 *   types and, through a dependency on its own feature, its contract once. Any other consumer lacks that request, and
 *   Gradle picks the api's own variants, which have fewer attributes.
 *
 * In one build a domain's tests see every api module of the build they reach, its own and its peers': under isolated
 * projects a domain cannot tell its own api from a peer's when it configures itself, and tests are no input of any
 * domain's main, so this adds no cycle. A peer in a repository of its own is no api module of the build: a test that
 * uses its generated code declares `testImplementation(wow.contract(...))`.
 *
 * None of these variants is published yet: the api's publication holds its types.
 */
internal object ContractRole {
    /** The feature of an api module's contract, whose capability is `<group>:<name>-contract`. */
    const val FEATURE = "contract"

    /** The [API_VIEW] value a consumer asks for to see every api's types with its contract. */
    const val CONTRACT_VIEW = "contract"

    /**
     * The declarable configuration of the contract variants' dependencies, the api's own types: the generated source
     * set's `api` configuration ([CONTRACT_SOURCE_SET]).
     */
    const val CONTRACT_API = "wowContractApi"

    /** The contract variants, of capability `<group>:<name>-contract`. */
    const val CONTRACT_API_ELEMENTS = "wowContractApiElements"
    const val CONTRACT_RUNTIME_ELEMENTS = "wowContractRuntimeElements"

    /** The variants of an api's types with its contract, which [CONTRACT_VIEW] selects. */
    const val VIEW_API_ELEMENTS = "wowContractViewApiElements"
    const val VIEW_RUNTIME_ELEMENTS = "wowContractViewRuntimeElements"

    /**
     * The contract of [api], which declares domain modules: [CONTRACT_JAR_TASK] packs what [assemble] keeps and, with
     * the Kotlin JVM plugin ([kotlin]), the code generated from it ([contractSources]); the contract variants offer the
     * jar, the view variants the api's types beside it.
     */
    fun contract(api: Project, main: SourceSet, assemble: TaskProvider<AssembleContract>, kotlin: Boolean) {
        val jar = api.tasks.register(CONTRACT_JAR_TASK, Jar::class.java) { jar ->
            jar.description = "Assembles this api module's contract and the code generated from it."
            jar.from(assemble)
            jar.archiveClassifier.set(FEATURE)
        }
        if (kotlin) {
            val generated = contractSources(api, main, assemble)
            jar.configure { it.from(generated.output) }
        }
        // The generated source set's own, which the Kotlin plugin creates: its code compiles against the api's types.
        val dependencies = api.configurations.findByName(CONTRACT_API)
            ?: api.configurations.create(CONTRACT_API) { configuration ->
                configuration.description = "The dependencies of this api module's contract: its own types."
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = false
            }
        dependencies.dependencies.add(api.dependencies.project(mapOf("path" to api.path)))
        val capability = "${api.group}:${api.name}-$FEATURE:${api.version}"
        listOf(CONTRACT_API_ELEMENTS to Usage.JAVA_API, CONTRACT_RUNTIME_ELEMENTS to Usage.JAVA_RUNTIME)
            .forEach { (name, usage) ->
                api.configurations.create(name) { elements ->
                    elements.description = "This api module's contract ($usage), of capability $capability."
                    elements.isCanBeConsumed = true
                    elements.isCanBeResolved = false
                    elements.extendsFrom(dependencies)
                    jvmAttributes(api, elements, usage)
                    elements.outgoing.capability(capability)
                    elements.outgoing.artifact(jar) { it.type = ArtifactTypeDefinition.JAR_TYPE }
                }
            }
        view(api)
    }

    /**
     * The view variants of [api]: its types (the `jar` task's) of the same dependencies, constraints and attributes as
     * `apiElements` and `runtimeElements`, and its own contract feature, of [API_VIEW] [CONTRACT_VIEW]. They follow
     * those variants lazily: their dependencies and constraints, and each attribute's value, are read when a consumer
     * resolves, so a JVM target set later (after this, in an `afterEvaluate` of the build script) is the view's too. A
     * view less fit than the api's own variant would be passed over, and its consumer left without the contract. The
     * attributes' keys are taken when [contract] runs, once the project is evaluated: the `java` plugin, which every api
     * module applies first, declares them all on creating the variants.
     */
    private fun view(api: Project) {
        val types = api.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java)
        val contract = api.dependencies.project(mapOf("path" to api.path)) as ModuleDependency
        contract.capabilities { it.requireFeature(FEATURE) }
        listOf(
            Triple(VIEW_API_ELEMENTS, Usage.JAVA_API, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME),
            Triple(VIEW_RUNTIME_ELEMENTS, Usage.JAVA_RUNTIME, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME),
        ).forEach { (name, usage, own) ->
            val elements = api.configurations.getByName(own)
            api.configurations.create(name) { view ->
                view.description = "This api module's types with its contract ($usage), which $API_VIEW " +
                    "$CONTRACT_VIEW selects."
                view.isCanBeConsumed = true
                view.isCanBeResolved = false
                view.dependencies.addAllLater(api.provider { elements.allDependencies.toList() })
                view.dependencyConstraints.addAllLater(api.provider { elements.allDependencyConstraints.toList() })
                view.dependencies.add(contract)
                jvmAttributes(api, view, usage)
                // The JVM version and environment the api's own variants declare, so that a consumer asking for
                // the view finds it no less fit than they are.
                elements.attributes.keySet().filter { view.attributes.getAttribute(it) == null }.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    val attribute = key as Attribute<Any>
                    view.attributes.attributeProvider(
                        attribute,
                        api.provider { elements.attributes.getAttribute(attribute) },
                    )
                }
                view.attributes.attribute(API_VIEW, CONTRACT_VIEW)
                view.outgoing.artifact(types) { it.type = ArtifactTypeDefinition.JAR_TYPE }
            }
        }
    }

    /**
     * Makes the class paths of [project]'s source sets that [selected] keeps ask for the [CONTRACT_VIEW] of every api
     * module: its types and its contract.
     */
    fun requestContracts(project: Project, selected: (SourceSet) -> Boolean) {
        project.dependencies.attributesSchema.attribute(API_VIEW)
        project.extensions.getByType(SourceSetContainer::class.java).matching(selected).configureEach { sourceSet ->
            listOf(sourceSet.compileClasspathConfigurationName, sourceSet.runtimeClasspathConfigurationName)
                .forEach { name ->
                    project.configurations.named(name) { classpath ->
                        classpath.attributes { it.attribute(API_VIEW, CONTRACT_VIEW) }
                    }
                }
        }
    }

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
