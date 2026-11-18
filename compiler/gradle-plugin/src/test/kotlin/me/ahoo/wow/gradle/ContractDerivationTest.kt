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
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
}
