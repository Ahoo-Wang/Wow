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

import me.ahoo.wow.gradle.WowPlugin.Companion.CONTRACT_ATTRIBUTE
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/**
 * The contracts a module's class paths hold without its build script naming them: the contract feature of every api
 * module among its dependencies, found by [CONTRACT_ATTRIBUTE] on the api's types variant, which names the feature.
 *
 * Per source set, the graph of its runtime dependencies ([graphName], metadata only, no artifacts) is resolved in a
 * configuration of its own; each component whose selected variant carries the attribute is added again, with its
 * feature required, to [derivedName], which the source set's compile and runtime class paths extend, and the module's
 * `runtimeElements` too for `main` ([outgoing]): whoever consumes a service module gets a class path that runs. A
 * project of the build is added as `project(path)`; a module, or a project of an included build, by its coordinates at
 * the version Gradle selected for its types, which an included build's substitution turns into its project. Types and
 * contract are one module, so they cannot drift.
 */
internal object ContractDerivation {
    /**
     * Derives the contracts of [project]'s source sets that [selected] keeps: of every api module, or of the build's own
     * projects alone when [projectsOnly] (a domain's tests, whose published peers they ask for themselves); `main`'s
     * also on the module's own runtime variant when [outgoing].
     */
    fun derive(
        project: Project,
        projectsOnly: Boolean,
        outgoing: Boolean = false,
        selected: (SourceSet) -> Boolean,
    ) {
        val dependencies = project.dependencies
        project.extensions.getByType(SourceSetContainer::class.java).matching(selected).configureEach { sourceSet ->
            val graph = project.configurations.create(graphName(sourceSet)) { graph ->
                graph.description = "The runtime dependencies of source set ${sourceSet.name}, whose api modules' " +
                    "contracts its class paths hold."
                graph.isCanBeConsumed = false
                graph.isTransitive = true
                graph.extendsFrom(
                    project.configurations.getByName(sourceSet.implementationConfigurationName),
                    project.configurations.getByName(sourceSet.runtimeOnlyConfigurationName),
                )
                graph.attributes { attributes ->
                    attributes.attribute(
                        Usage.USAGE_ATTRIBUTE,
                        project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
                    )
                    attributes.attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        project.objects.named(Category::class.java, Category.LIBRARY),
                    )
                }
            }
            val derived = project.configurations.create(derivedName(sourceSet)) { derived ->
                derived.description = "The contracts of the api modules source set ${sourceSet.name} depends on."
                derived.isCanBeConsumed = false
                derived.isCanBeResolved = false
                derived.dependencies.addAllLater(
                    graph.incoming.resolutionResult.rootComponent.map { root ->
                        contractsOf(root, dependencies, projectsOnly)
                    },
                )
            }
            val into = listOf(sourceSet.compileClasspathConfigurationName, sourceSet.runtimeClasspathConfigurationName) +
                listOfNotNull(
                    JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
                        .takeIf { outgoing && sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME },
                )
            into.forEach { name -> project.configurations.named(name) { it.extendsFrom(derived) } }
        }
    }

    /**
     * The contract dependencies of the components [root] reaches whose selected variant carries [CONTRACT_ATTRIBUTE]:
     * of the build's own projects alone when [projectsOnly].
     */
    internal fun contractsOf(
        root: ResolvedComponentResult,
        dependencies: DependencyHandler,
        projectsOnly: Boolean,
    ): List<Dependency> {
        val build = (root.id as? ProjectComponentIdentifier)?.build?.buildPath
        val seen = mutableSetOf(root.id)
        val pending = ArrayDeque(listOf(root))
        val contracts = mutableListOf<Dependency>()
        while (pending.isNotEmpty()) {
            pending.removeFirst().dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }
                .filter { seen.add(it.id) }
                .forEach { component ->
                    pending += component
                    val feature = featureOf(component) ?: return@forEach
                    val id = component.id
                    val local = id is ProjectComponentIdentifier && id.build.buildPath == build
                    val contract = when {
                        local -> dependencies.project(mapOf("path" to (id as ProjectComponentIdentifier).projectPath))
                        projectsOnly -> null
                        else -> component.moduleVersion?.let { dependencies.create("$it") }
                    } as ModuleDependency? ?: return@forEach
                    contract.capabilities { it.requireFeature(feature) }
                    contracts += contract
                }
        }
        return contracts
    }

    /** The feature [component]'s selected variant names in [CONTRACT_ATTRIBUTE]; null for any other component. */
    private fun featureOf(component: ResolvedComponentResult): String? =
        component.variants.firstNotNullOfOrNull { variant ->
            variant.attributes.keySet().firstOrNull { it.name == CONTRACT_ATTRIBUTE.name }
                ?.let { variant.attributes.getAttribute(it)?.toString() }
        }

    /** The graph of [sourceSet]'s runtime dependencies: `wowApiTypes` for `main`, `wowTestApiTypes` for `test`. */
    internal fun graphName(sourceSet: SourceSet): String = sourceSet.getTaskName("wow", "ApiTypes")

    /** The contracts derived for [sourceSet]: `wowDerivedContracts` for `main`, `wowTestDerivedContracts` for `test`. */
    internal fun derivedName(sourceSet: SourceSet): String = sourceSet.getTaskName("wow", "DerivedContracts")
}
