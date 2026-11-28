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
import me.ahoo.wow.gradle.WowPlugin.Companion.ASSEMBLE_CONTRACT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACTS_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_DOCS_TYPE
import me.ahoo.wow.gradle.WowPlugin.Companion.KOTLIN_JVM
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.WriteProperties

/**
 * The api role of a project ([api]: `me.ahoo.wow.api`): a `java-library` of the types another context compiles
 * against, which pairs with the domain modules it declares ([WowPlugin.CONTRACT_CONFIGURATION]) and offers their
 * contract apart from its types ([ContractRole]).
 */
internal object ApiRole {
    /**
     * The api role of [project]: a `java-library` with the Wow dependencies, the declarable [CONTRACT_CONFIGURATION]
     * of its domain modules and the resolvable [CONTRACTS_CONFIGURATION] of their contracts, the contract it assembles
     * from them, offered with the code generated from it as its contract feature ([ContractRole.contract]) when it
     * declares any, and the in-build pairing it tells its domain modules ([pairing]). Its own variants, `apiElements`
     * and `runtimeElements`, hold its types alone: a module of no domain declared is a library of types.
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
            task.contracts.from(contractsOf(contracts))
            task.owners.set(ownersOf(contracts))
            task.classes.from(main.output.classesDirs)
            task.owner.set(project.path)
            task.tools.from(toolsConfiguration(project))
            task.outputDirectory.set(project.layout.buildDirectory.dir("wow/contract"))
        }
        pairing(project)
        // Once evaluated, the domain modules are declared and the Kotlin plugin applied, its source set extensions in
        // place, which they are not yet while it applies the `java` plugin.
        // A domain module declared later, after the evaluation, still gets its contract.
        evaluated(project) {
            var offered = false
            declared.dependencies.configureEach {
                if (!offered) {
                    offered = true
                    ContractRole.contract(project, main, assemble, project.pluginManager.hasPlugin(KOTLIN_JVM))
                }
            }
        }
    }

    /**
     * The in-build pairing of [api]: [PAIRING_ELEMENTS], a variant of [PAIRING_USAGE] holding the api's own path (`api`)
     * and the paths of the domain modules it declares in [CONTRACT_CONFIGURATION] (`domains`, comma-separated), which a
     * domain module reads to know the api modules that carry its contract. The api's path is in the file, so a pairing
     * that moves to another api module changes what the domain module's contract task reads (#6844). It is no part of
     * the api's component, so no publication names a project path.
     */
    private fun pairing(api: Project) {
        val declared = api.configurations.getByName(CONTRACT_CONFIGURATION)
        val write = api.tasks.register(PAIRING_TASK, WriteProperties::class.java) { task ->
            task.description = "Writes the domain modules this api module pairs with, which they read in-build."
            task.destinationFile.set(api.layout.buildDirectory.file("wow/pairing.properties"))
            task.property(PAIRING_API, api.path)
            task.property(
                PAIRING_DOMAINS,
                api.provider {
                    declared.dependencies.withType(ProjectDependency::class.java).map { it.path }.sorted()
                        .joinToString(",")
                },
            )
        }
        api.configurations.create(PAIRING_ELEMENTS) { elements ->
            elements.description = "The domain modules this api module pairs with, for the build's domain modules."
            elements.isCanBeConsumed = true
            elements.isCanBeResolved = false
            elements.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, api.objects.named(Usage::class.java, PAIRING_USAGE))
                it.attribute(Category.CATEGORY_ATTRIBUTE, api.objects.named(Category::class.java, PAIRING_USAGE))
            }
            elements.outgoing.artifact(write.flatMap { it.destinationFile })
        }
    }

    /** Runs [action] once [project] is evaluated, at once when it already is. */
    private fun evaluated(project: Project, action: () -> Unit) {
        if (project.state.executed) {
            action()
        } else {
            project.afterEvaluate { action() }
        }
    }

    private const val API = "wow-api"
    private const val PAIRING_TASK = "writeWowPairing"
    internal const val PAIRING_ELEMENTS = "wowPairingElements"

    /** The usage and category of the in-build pairing variant, which only a domain module's pairing view asks for. */
    internal const val PAIRING_USAGE = "wow-pairing"

    /** The property of the pairing file that names its api module's path. */
    internal const val PAIRING_API = "api"

    /** The property of the pairing file that lists the domain modules' paths. */
    internal const val PAIRING_DOMAINS = "domains"

    /**
     * The file a domain module's contract variant carries beside its contract: the package of each of its contexts'
     * generated code ([WowExtension.contractPackage]), `<context>=<package>` lines, which the api module's
     * [GenerateContractSources] reads. A build input of the plugin's own: never published, no
     * other tool reads it, and it has no schema and no version.
     */
    const val CONTRACT_PACKAGES_FILE = "contract-package.properties"

    /** The domain modules' contracts among the files of [contracts] ([CONTRACTS_CONFIGURATION]). */
    fun contractsOf(contracts: Configuration): FileCollection = contracts.filter { it.name != CONTRACT_PACKAGES_FILE }

    /** The domain modules' [CONTRACT_PACKAGES_FILE]s among the files of [contracts] ([CONTRACTS_CONFIGURATION]). */
    fun contractPackagesOf(contracts: Configuration): FileCollection =
        contracts.filter { it.name == CONTRACT_PACKAGES_FILE }

    /** The peer contract an api module's contract jar carries. */
    const val CONTRACT_RESOURCE = "META-INF/wow/contract.json"
}
