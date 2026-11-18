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
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.DocsType
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The api module's plugin, `me.ahoo.wow.api` (decision D363 ①), on projects the builder makes. */
class WowApiPluginTest {
    @TempDir
    lateinit var directory: File

    /**
     * A build of `:shop-api` (`me.ahoo.wow.api`, Kotlin) declaring the domain modules [domains] in `wowContract`, each
     * depending on it in `api(...)`.
     */
    private fun paired(vararg domains: String): Project {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = ProjectBuilder.builder().withParent(root).withName("shop-api")
            .withProjectDir(File(directory, "shop-api")).build()
        api.pluginManager.apply(WowPlugin.API_ID)
        api.pluginManager.apply("org.jetbrains.kotlin.jvm")
        domains.forEach { name ->
            val domain = ProjectBuilder.builder().withParent(root).withName(name)
                .withProjectDir(File(directory, name)).build()
            domain.pluginManager.apply(WowPlugin.ID)
            domain.pluginManager.apply("org.jetbrains.kotlin.jvm")
            domain.dependencies.add("api", domain.dependencies.project(mapOf("path" to ":shop-api")))
            domain.dependencies.add("implementation", "sample:library:1.0.0")
            api.dependencies.add(WowPlugin.CONTRACT_CONFIGURATION, api.dependencies.project(mapOf("path" to ":$name")))
        }
        (api as ProjectInternal).evaluate()
        return api
    }

    @Test
    fun `an api project of a domain module should carry its contract`() {
        val api = paired("shop-domain", "audit-domain")
        val declared = api.configurations.getByName(WowPlugin.CONTRACT_CONFIGURATION)
        declared.isCanBeConsumed.assert().isFalse()
        declared.isCanBeResolved.assert().isFalse()
        val contracts = api.configurations.getByName(WowPlugin.CONTRACTS_CONFIGURATION)
        contracts.isCanBeConsumed.assert().isFalse()
        contracts.isTransitive.assert().isFalse()
        contracts.attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE)?.name
            .assert().isEqualTo(WowPlugin.CONTRACT_DOCS_TYPE)
        contracts.allDependencies.map { it.name }.assert().containsExactlyInAnyOrder("shop-domain", "audit-domain")
        val assemble = api.tasks.getByName(WowPlugin.ASSEMBLE_CONTRACT_TASK) as AssembleContract
        assemble.owner.get().assert().isEqualTo(":shop-api")
        assemble.outputDirectory.get().asFile.canonicalFile
            .assert().isEqualTo(File(directory, "shop-api/build/wow/contract").canonicalFile)
        api.tasks.getByName(WowPlugin.CONTRACT_JAR_TASK).taskDependencies.getDependencies(null).map { it.name }
            .assert().contains(WowPlugin.ASSEMBLE_CONTRACT_TASK)
        // The api's own jar holds its types alone.
        (api.tasks.getByName("jar") as Jar).taskDependencies.getDependencies(null).map { it.name }
            .assert().doesNotContain(WowPlugin.ASSEMBLE_CONTRACT_TASK)
        // A module dependency is no api module of the domain.
        api.rootProject.allprojects.mapNotNull { it.configurations.findByName(WowPlugin.CONTRACT_CONFIGURATION) }
            .assert().hasSize(1)
    }

    @Test
    fun `an api project should compile the code generated from its contract in a source set of its own`() {
        val api = paired("shop-domain", "audit-domain")
        val sourceSets = api.extensions.getByType(SourceSetContainer::class.java)
        val generated = sourceSets.getByName(WowPlugin.CONTRACT_SOURCE_SET)
        val generate = api.tasks.getByName(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK) as GenerateContractSources
        generate.contract.files.single().invariantSeparatorsPath.assert()
            .endsWith("shop-api/build/wow/contract/${PeerContracts.RESOURCE}")
        generate.taskDependencies.getDependencies(null).map { it.name }
            .assert().contains(WowPlugin.ASSEMBLE_CONTRACT_TASK)
        // It compiles against the api's main classes and dependencies.
        generated.compileClasspath.buildDependencies.getDependencies(null).map { it.name }
            .assert().contains("compileKotlin")
        api.configurations.getByName(generated.implementationConfigurationName).extendsFrom.map { it.name }
            .assert().contains("implementation")
        // The api's own variants and jar hold its types; the contract jar holds the generated code.
        (api.tasks.getByName("jar") as Jar).taskDependencies.getDependencies(null).map { it.name }
            .assert().doesNotContain("compileWowContractKotlin")
        api.tasks.getByName(WowPlugin.CONTRACT_JAR_TASK).taskDependencies.getDependencies(null).map { it.name }
            .assert().contains("compileWowContractKotlin")
        val wowContractClasses = File(directory, "shop-api/build/classes/kotlin/wowContract").canonicalFile
        listOf("apiElements", "runtimeElements").forEach { name ->
            val elements = api.configurations.getByName(name)
            elements.outgoing.variants.flatMap { it.artifacts }.map { it.file.canonicalFile }
                .assert().doesNotContain(wowContractClasses)
            // ... and name the feature that holds the module's contract, for the services to find it.
            elements.attributes.getAttribute(WowPlugin.CONTRACT_ATTRIBUTE).assert().isEqualTo(ContractRole.FEATURE)
        }
        // The contract, the api's feature: its capability, and the api's types as its dependency.
        listOf(ContractRole.CONTRACT_API_ELEMENTS, ContractRole.CONTRACT_RUNTIME_ELEMENTS).forEach { name ->
            val elements = api.configurations.getByName(name)
            elements.outgoing.capabilities.map { "${it.group}:${it.name}" }
                .assert().containsExactly("${api.group}:shop-api-contract")
            elements.allDependencies.withType(ProjectDependency::class.java).map { it.path }
                .assert().containsExactly(":shop-api")
        }
        // The module publishes the contract within itself, as a feature.
        (api.components.getByName("java") as SoftwareComponentInternal).usages.map { it.name }.assert()
            .contains(ContractRole.CONTRACT_API_ELEMENTS, ContractRole.CONTRACT_RUNTIME_ELEMENTS)
        // The domain module compiles against the types; its tests see the contracts derived for them.
        val domain = api.rootProject.project(":shop-domain")
        val derived = "wowTestDerivedContracts"
        domain.configurations.getByName("compileClasspath").extendsFrom.map { it.name }
            .assert().doesNotContain(derived)
        listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
            domain.configurations.getByName(name).extendsFrom.map { it.name }.assert().contains(derived)
        }
        // The pairing it tells its domain modules, in-build.
        api.configurations.getByName(ApiRole.PAIRING_ELEMENTS).isCanBeConsumed.assert().isTrue()
    }

    @Test
    fun `generating the contract sources should write one object per context`() {
        val api = paired("shop-domain")
        val task = api.tasks.getByName(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK) as GenerateContractSources
        val output = task.outputDirectory.get().asFile
        File(output, "stale.kt").apply { parentFile.mkdirs() }.writeText("stale")
        task.contract.setFrom(contractFile("shop-cart", "sample.AddItem"), File(directory, "missing.json"))
        // The generator of the api's own tools, which compiles against the api's main classes and dependencies.
        task.tools.from.assert().contains(api.configurations.getByName(WowPlugin.TOOLS_CONFIGURATION))
        task.classpath.buildDependencies.getDependencies(null).map { it.name }.assert().contains("compileKotlin")
        // Without wow-query-api on it (the builder resolves no repository): no query fields.
        task.classpath.setFrom()

        task.submit(inProcessQueue(api))

        File(output, "stale.kt").exists().assert().isFalse()
        File(output, "sample/ShopCartContract.kt").readText().assert().contains(
            "package sample",
            "object ShopCartContract",
            "const val NAME: kotlin.String = \"shop-cart\"",
            "object Cart",
            "public const val NAME: kotlin.String = \"cart\"",
            "AggregateType(CONTEXT, NAME)",
            "fun ref(tenantId: kotlin.String, id: kotlin.String): me.ahoo.wow.api.aggregate.AggregateRef = TYPE.ref(tenantId, id)",
        )
    }

    @Test
    fun `an api project declaring a domain module after its evaluation should offer its contract`() {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = ProjectBuilder.builder().withParent(root).withName("shop-api")
            .withProjectDir(File(directory, "shop-api")).build()
        api.pluginManager.apply(WowPlugin.API_ID)
        api.pluginManager.apply("org.jetbrains.kotlin.jvm")
        (api as ProjectInternal).evaluate()
        ProjectBuilder.builder().withParent(root).withName("shop-domain")
            .withProjectDir(File(directory, "shop-domain")).build()
        api.dependencies.add(
            WowPlugin.CONTRACT_CONFIGURATION,
            api.dependencies.project(mapOf("path" to ":shop-domain"))
        )
        api.tasks.findByName(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK).assert().isNotNull()
        api.configurations.findByName(ContractRole.CONTRACT_API_ELEMENTS).assert().isNotNull()
    }

    @Test
    fun `an api project of no domain module should be a library of types, with no contract`() {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = ProjectBuilder.builder().withParent(root).withName("ids-api")
            .withProjectDir(File(directory, "ids-api")).build()
        api.pluginManager.apply(WowPlugin.API_ID)
        api.pluginManager.apply("org.jetbrains.kotlin.jvm")
        (api as ProjectInternal).evaluate()
        listOf(ContractRole.CONTRACT_API_ELEMENTS, ContractRole.CONTRACT_API)
            .forEach { api.configurations.findByName(it).assert().describedAs(it).isNull() }
        api.configurations.getByName("apiElements").attributes.getAttribute(WowPlugin.CONTRACT_ATTRIBUTE)
            .assert().isNull()
        api.tasks.findByName(WowPlugin.CONTRACT_JAR_TASK).assert().isNull()
        api.tasks.findByName(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK).assert().isNull()
    }

    @Test
    fun `an api project without the Kotlin plugin should carry no generated code`() {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = ProjectBuilder.builder().withParent(root).withName("java-api")
            .withProjectDir(File(directory, "java-api")).build()
        api.pluginManager.apply("java")
        api.pluginManager.apply(WowPlugin.API_ID)
        ProjectBuilder.builder().withParent(root).withName("domain").withProjectDir(File(directory, "domain")).build()
        api.dependencies.add(WowPlugin.CONTRACT_CONFIGURATION, api.dependencies.project(mapOf("path" to ":domain")))
        (api as ProjectInternal).evaluate()
        api.tasks.findByName(WowPlugin.ASSEMBLE_CONTRACT_TASK).assert().isNotNull()
        api.tasks.findByName(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK).assert().isNull()
        // Its contract jar holds the contract alone.
        api.tasks.findByName(WowPlugin.CONTRACT_JAR_TASK).assert().isNotNull()
        api.configurations.findByName(ContractRole.CONTRACT_API_ELEMENTS).assert().isNotNull()
    }

    private fun contractFile(name: String, vararg classes: String): File =
        File(directory, "contracts/$name/contract.json").also { file ->
            val aggregate = AggregateManifest(
                name = "cart",
                state = StateManifest(Manifest.schemaRef("sample.CartState")),
                commands = classes.map { CommandManifest(it.substringAfterLast('.'), schema = Manifest.schemaRef(it)) },
            )
            ManifestJson.write(
                Manifest(
                    contexts = listOf(ContextManifest(name, aggregates = listOf(aggregate))),
                    defs = mapOf("sample.CartState" to mapOf("type" to "object")),
                ),
                file,
            )
        }

    private fun assembleTask(vararg contracts: File): AssembleContract {
        val api = paired("shop-domain")
        val classes = File(directory, "api-classes")
        File(classes, "sample/AddItem.class").apply { parentFile.mkdirs() }.writeText("")
        return (api.tasks.getByName(WowPlugin.ASSEMBLE_CONTRACT_TASK) as AssembleContract).apply {
            this.contracts.setFrom(*contracts)
            // The domain module each contract comes from, as the build resolves it once the domain's task has run; a
            // contract of no known owner (the second) is named by its file.
            this.owners.set(contracts.take(1).associate { it.path to "project ':${it.nameWithoutExtension}-domain'" })
            this.classes.setFrom(classes, File(directory, "missing"))
        }
    }

    @Test
    fun `assembling should keep the contexts whose classes are the api module's`() {
        val task = assembleTask(contractFile("shop", "sample.AddItem"), contractFile("billing", "sample.Pay"))
        val output = File(directory, "shop-api/build/wow/contract")
        File(output, "stale.txt").apply { parentFile.mkdirs() }.writeText("stale")

        task.submit(inProcessQueue(task.project))

        File(output, "stale.txt").exists().assert().isFalse()
        val contract = ManifestJson.read(File(output, PeerContracts.RESOURCE))
        contract.contexts.map { it.name }.assert().containsExactly("shop")
        contract.defs.keys.assert().containsExactly("sample.CartState")
    }

    @Test
    fun `assembling no context of the api module should write no contract`() {
        val task = assembleTask(contractFile("billing", "sample.Pay"))
        task.submit(inProcessQueue(task.project))
        File(directory, "shop-api/build/wow/contract").list().orEmpty().toList().assert().isEmpty()
    }

    @Test
    fun `assembling a context with classes outside the api module should fail naming them`() {
        val task = assembleTask(contractFile("shop", "sample.AddItem", "sample.RemoveItem"))
        assertThrows<IllegalStateException> { task.submit(inProcessQueue(task.project)) }.message.assert()
            .contains(
                "context shop of project ':",
                "has classes that are not in project ':shop-api': sample.RemoveItem",
            )
    }

    @Test
    fun `an api module's sources jar should hold its own sources alone`() {
        val api = paired("shop-domain")
        api.extensions.getByType(JavaPluginExtension::class.java).withSourcesJar()
        val sources = api.tasks.getByName("sourcesJar") as Jar
        sources.taskDependencies.getDependencies(sources).map { it.name }.assert()
            .doesNotContain(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK)
    }

    @Test
    fun `an api module declaring a module of no contract should fail, naming it`() {
        val empty = File(directory, "contracts/empty/contract.json").also { ManifestJson.write(Manifest(), it) }
        val task = assembleTask(contractFile("shop", "sample.AddItem"), empty)
        task.owners.set(mapOf(empty.path to "project ':gateway'"))
        assertThrows<IllegalStateException> { task.submit(inProcessQueue(task.project)) }.message.assert().contains(
            "project ':shop-api' declares project ':gateway' in wowContract, which compiles no model",
        )
    }

    @Test
    fun `an api module should add the BOM of the plugin's version and wow-api, unless declared`() {
        val api = paired()
        api.configurations.getByName("api").incoming.dependencies.filter { it.group == "me.ahoo.wow" }
            .map { "${it.group}:${it.name}:${it.version}" }.assert()
            .containsExactlyInAnyOrder("me.ahoo.wow:wow-api:null", "me.ahoo.wow:wow-bom:${WowPlugin.version}")
    }

    @Test
    fun `an api module should tell its domain modules in-build that it pairs with them`() {
        val api = paired("shop-domain", "audit-domain")
        val write = api.tasks.getByName("writeWowPairing") as WriteProperties
        write.properties.assert().containsEntry(ApiRole.PAIRING_DOMAINS, ":audit-domain,:shop-domain")
        val elements = api.configurations.getByName(ApiRole.PAIRING_ELEMENTS)
        elements.isCanBeResolved.assert().isFalse()
        elements.outgoing.artifacts.files.singleFile.name.assert().isEqualTo("pairing.properties")
        // The domain module's contract task finds it among its compile class path's projects: the api's pairing file,
        // no other variant of the project.
        val domain = api.rootProject.project(":shop-domain")
        (domain as ProjectInternal).evaluate()
        val view = DomainRole.pairingOf(domain, domain.configurations.getByName("compileClasspath"))
        val pairings = DomainRole.pairings(view.artifacts.artifacts)
        pairings.assert().hasSize(1).containsValue(":shop-api")
        pairings.keys.single().assert().endsWith("pairing.properties")
    }

    /** `:shop-api` of `shop-domain`, publishing sources, with the Gradle properties [properties] of its build. */
    private fun publishing(properties: String = ""): Project {
        File(directory, "gradle.properties").writeText(properties)
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = ProjectBuilder.builder().withParent(root).withName("shop-api")
            .withProjectDir(File(directory, "shop-api")).build()
        api.pluginManager.apply(WowPlugin.API_ID)
        api.pluginManager.apply("org.jetbrains.kotlin.jvm")
        api.extensions.getByType(JavaPluginExtension::class.java).withSourcesJar()
        ProjectBuilder.builder().withParent(root).withName("shop-domain")
            .withProjectDir(File(directory, "shop-domain")).build()
        val domain = api.dependencies.project(mapOf("path" to ":shop-domain"))
        api.dependencies.add(WowPlugin.CONTRACT_CONFIGURATION, domain)
        (api as ProjectInternal).evaluate()
        return api
    }

    @Test
    fun `an api module publishing sources should publish its contract's sources within its feature`() {
        val api = publishing()
        val sources = api.configurations.getByName(ContractRole.CONTRACT_SOURCES_ELEMENTS)
        sources.outgoing.capabilities.map { it.name }.assert().containsExactly("shop-api-contract")
        sources.attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE)?.name.assert().isEqualTo(DocsType.SOURCES)
        val jar = api.tasks.getByName("wowContractSourcesJar") as Jar
        jar.archiveClassifier.get().assert().isEqualTo("contract-sources")
        (api.components.getByName("java") as SoftwareComponentInternal).usages.map { it.name }.assert()
            .contains(ContractRole.CONTRACT_SOURCES_ELEMENTS)
    }

    @Test
    fun `an api module's first release of a cycle should publish its types alone`() {
        val api = publishing("${ContractRole.PUBLISH_PROPERTY}=false\n")
        (api.components.getByName("java") as SoftwareComponentInternal).usages.map { it.name }.assert()
            .doesNotContain(ContractRole.CONTRACT_API_ELEMENTS, ContractRole.CONTRACT_SOURCES_ELEMENTS)
        api.configurations.getByName("apiElements").attributes.getAttribute(WowPlugin.CONTRACT_ATTRIBUTE)
            .assert().isNull()
        // In-build, the contract is still its feature.
        api.configurations.findByName(ContractRole.CONTRACT_API_ELEMENTS).assert().isNotNull()
    }
}
