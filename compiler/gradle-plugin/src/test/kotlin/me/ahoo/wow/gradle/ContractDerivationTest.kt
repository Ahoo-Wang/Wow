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

import me.ahoo.test.asserts.assert
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ComponentSelectionReason
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Optional

/**
 * The contracts derived for a service and for a domain's tests ([ContractDerivation]), on projects the builder makes:
 * `shop-api` declares `shop-domain`, `shop-server` hosts it.
 */
class ContractDerivationTest {
    @TempDir
    lateinit var directory: File

    private fun child(root: Project, name: String, vararg plugins: String): Project =
        ProjectBuilder.builder().withParent(root).withName(name).withProjectDir(File(directory, name)).build().also {
            plugins.forEach(it.pluginManager::apply)
        }

    /** The build, evaluated: the api, the domain and the server. */
    private fun build(): Map<String, Project> {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = child(root, "shop-api", WowPlugin.API_ID, WowPlugin.KOTLIN_JVM)
        val domain = child(root, "shop-domain", WowPlugin.ID, WowPlugin.KOTLIN_JVM)
        val server = child(root, "shop-server", WowPlugin.SERVICE_ID, WowPlugin.KOTLIN_JVM)
        domain.dependencies.add("api", domain.dependencies.project(mapOf("path" to ":shop-api")))
        server.dependencies.add("implementation", server.dependencies.project(mapOf("path" to ":shop-domain")))
        val declared = api.dependencies.project(mapOf("path" to ":shop-domain"))
        api.dependencies.add(WowPlugin.CONTRACT_CONFIGURATION, declared)
        listOf(api, domain, server).forEach { (it as ProjectInternal).evaluate() }
        return mapOf("api" to api, "domain" to domain, "server" to server)
    }

    /** The contracts [project] derives from the graph of its main runtime dependencies. */
    private fun derived(project: Project, projectsOnly: Boolean): List<ModuleDependency> {
        val graph = project.configurations.getByName("wowApiTypes")
        return ContractDerivation.contractsOf(graph.incoming.resolutionResult.root, project.dependencies, projectsOnly)
            .map { it as ModuleDependency }
    }

    @Test
    fun `a service should derive the contract feature of every api module it reaches`() {
        val server = build().getValue("server")
        val contracts = derived(server, projectsOnly = false)
        contracts.map { (it as ProjectDependency).path }.assert().containsExactly(":shop-api")
        contracts.single().capabilitySelectors.single().displayName.assert().contains(ContractRole.FEATURE)
        derived(server, projectsOnly = true).map { (it as ProjectDependency).path }
            .assert().containsExactly(":shop-api")
        // The configuration its class paths extend holds them, added when Gradle reads its dependencies.
        server.configurations.getByName("wowDerivedContracts").dependencies.map { (it as ProjectDependency).path }
            .assert().containsExactly(":shop-api")
        // Its class paths and its runtime variant hold them; no api module's own variants are asked for anything.
        listOf("compileClasspath", "runtimeClasspath", "runtimeElements").forEach { name ->
            server.configurations.getByName(name).extendsFrom.map { it.name }.assert().describedAs(name)
                .contains("wowDerivedContracts")
        }
        listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
            server.configurations.getByName(name).extendsFrom.map { it.name }
                .assert().contains("wowTestDerivedContracts")
        }
    }

    @Test
    fun `a domain should derive the contracts of its tests alone, its main reaching none`() {
        val domain = build().getValue("domain")
        domain.configurations.findByName("wowDerivedContracts").assert().isNull()
        domain.configurations.getByName("testRuntimeClasspath").extendsFrom.map { it.name }.assert()
            .contains("wowTestDerivedContracts")
        domain.configurations.getByName("runtimeElements").extendsFrom.map { it.name }.assert()
            .doesNotContain("wowTestDerivedContracts")
    }

    /** A project of the build at [buildPath], as a resolution result identifies it. */
    private class ProjectId(private val buildPath: String, private val path: String) : ProjectComponentIdentifier {
        override fun getBuild(): BuildIdentifier = BuildIdentifier { buildPath }
        override fun getProjectPath(): String = path
        override fun getBuildTreePath(): String = path
        override fun getProjectName(): String = path.substringAfterLast(':')
        override fun getDisplayName(): String = "project $path"
    }

    /** A module, as a resolution result identifies it. */
    private class ModuleId(private val coordinates: String) : ComponentIdentifier, ModuleVersionIdentifier {
        private val parts = coordinates.split(':')
        override fun getDisplayName(): String = coordinates
        override fun getGroup(): String = parts[0]
        override fun getName(): String = parts[1]
        override fun getVersion(): String = parts[2]
        override fun getModule(): ModuleIdentifier = throw UnsupportedOperationException()
        override fun toString(): String = coordinates
    }

    /** A component of [id], its selected variant of [variantAttributes], reaching [reaches]. */
    private class Component(
        private val id: ComponentIdentifier,
        private val variantAttributes: AttributeContainer,
        private val reaches: List<ResolvedComponentResult> = emptyList(),
    ) : ResolvedComponentResult {
        override fun getId(): ComponentIdentifier = id
        override fun getModuleVersion(): ModuleVersionIdentifier? = id as? ModuleVersionIdentifier
        override fun getVariants(): List<ResolvedVariantResult> = listOf(
            object : ResolvedVariantResult {
                override fun getOwner(): ComponentIdentifier = id
                override fun getAttributes(): AttributeContainer = variantAttributes
                override fun getDisplayName(): String = "runtimeElements"
                override fun getCapabilities(): List<Capability> = emptyList()
                override fun getExternalVariant(): Optional<ResolvedVariantResult> = Optional.empty()
            },
        )
        override fun getDependencies(): Set<DependencyResult> = reaches.map { selected ->
            object : ResolvedDependencyResult {
                override fun getSelected(): ResolvedComponentResult = selected
                override fun getResolvedVariant(): ResolvedVariantResult = selected.variants.single()
                override fun getRequested(): ComponentSelector = throw UnsupportedOperationException()
                override fun getFrom(): ResolvedComponentResult = this@Component
                override fun isConstraint(): Boolean = false
            }
        }.toSet()
        override fun getDependents(): Set<ResolvedDependencyResult> = emptySet()
        override fun getSelectionReason(): ComponentSelectionReason = throw UnsupportedOperationException()
        override fun getDependenciesForVariant(variant: ResolvedVariantResult): List<DependencyResult> =
            getDependencies().toList()
    }

    @Test
    fun `a published api module, or a project of an included build, should be derived by its selected coordinates`() {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        val none = project.configurations.detachedConfiguration().attributes
        val api = project.configurations.detachedConfiguration().attributes
            .attribute(WowPlugin.CONTRACT_ATTRIBUTE, ContractRole.FEATURE)
        val published = Component(ModuleId("sample:shop-api:1.2.0"), api)
        val library = Component(ModuleId("sample:library:1.0.0"), none)
        // A project of an included build is found by its coordinates, which the build's substitution turns back.
        val included = Component(ModuleId("sample:billing-api:2.0.0"), api)
        val other = Component(ProjectId(":billing", ":billing-api"), api, listOf(included))
        val root = Component(ProjectId(":", ":shop-server"), none, listOf(published, library, other))
        val contracts = ContractDerivation.contractsOf(root, project.dependencies, projectsOnly = false)
            .map { it as ExternalModuleDependency }
        contracts.map { "${it.group}:${it.name}:${it.version}" }.assert()
            .containsExactly("sample:shop-api:1.2.0", "sample:billing-api:2.0.0")
        contracts.forEach { it.capabilitySelectors.single().displayName.assert().contains(ContractRole.FEATURE) }
        // A domain's tests take the build's own projects alone.
        ContractDerivation.contractsOf(root, project.dependencies, projectsOnly = true).assert().isEmpty()
    }
}
