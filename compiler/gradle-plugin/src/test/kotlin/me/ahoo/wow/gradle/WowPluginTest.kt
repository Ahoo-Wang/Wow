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

import groovy.json.JsonSlurper
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The plugin's wiring, in process (ProjectBuilder); [WowPluginFunctionalTest] builds a real service with it. */
class WowPluginTest {
    @TempDir
    lateinit var directory: File

    /** A project of [id], the service plugin by default: the domain role on its sources, and the service's. */
    private fun project(kotlin: Boolean = true, id: String = WowPlugin.SERVICE_ID): Project =
        wowProject(directory, kotlin, id)

    @Test
    fun `the tasks should complete each fragment and merge them`() {
        val project = project()
        val skeleton = File(directory, "build/generated/ksp/main/resources/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        ManifestJson.write(Manifest(), skeleton)
        val generate = project.tasks.getByName(WowPlugin.GENERATE_FRAGMENT_TASK) as GenerateManifestFragment
        val stale = File(directory, "build/wow/manifest-fragment/${Manifest.FRAGMENT_DIRECTORY}/stale.json")
        stale.apply { parentFile.mkdirs() }.writeText("{}")
        val queue = inProcessQueue(project)

        generate.submit(queue)

        stale.exists().assert().isFalse()
        val fragment = File(directory, "build/wow/manifest-fragment/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        fragment.isFile.assert().isTrue()

        val merge = project.tasks.getByName(WowPlugin.MERGE_TASK) as MergeManifest
        merge.classpath.setFrom(generate.outputDirectory)
        // The owners the build resolves once the tasks producing the class path have run.
        merge.owners.set(emptyMap())
        merge.submit(queue)
        ManifestJson.read(File(directory, "build/wow/manifest.json")).service?.name.assert().isEqualTo("shop")
    }

    @Test
    fun `the OpenAPI task should write the document of the merged Manifest`() {
        val project = project()
        val openApi = project.tasks.getByName(WowPlugin.OPENAPI_TASK) as GenerateOpenApi
        openApi.group.assert().isEqualTo("wow")
        val manifest = File(project.projectDir, "build/wow/manifest.json")
        openApi.manifestFile.get().asFile.assert().isEqualTo(manifest)
        val output = File(project.projectDir, "build/wow/openapi.json")
        openApi.outputFile.get().asFile.assert().isEqualTo(output)
        ManifestJson.write(Manifest(service = ServiceInfo("shop", "1.0.0"), errors = FrameworkErrors.ALL), manifest)

        openApi.submit(inProcessQueue(project))

        val document = JsonSlurper().parse(output) as Map<*, *>
        document["openapi"].assert().isEqualTo("3.1.0")
        (document["info"] as Map<*, *>)["title"].assert().isEqualTo("shop")
        (document["paths"] as Map<*, *>).keys.assert().contains("/wow/openapi")
    }

    @Test
    fun `the Helm values task should write the chart's values of the merged Manifest`() {
        val project = project()
        val values = project.tasks.getByName(WowPlugin.HELM_VALUES_TASK) as GenerateHelmValues
        values.group.assert().isEqualTo("wow")
        val manifest = File(project.projectDir, "build/wow/manifest.json")
        values.manifestFile.get().asFile.assert().isEqualTo(manifest)
        val output = File(project.projectDir, "build/wow/helm/values.yaml")
        values.outputFile.get().asFile.assert().isEqualTo(output)
        ManifestJson.write(Manifest(service = ServiceInfo("shop-service", "1.0.0")), manifest)
        values.classpath.setFrom()

        values.submit(inProcessQueue(project))

        output.readText().assert().contains("  name: \"shop-service\"", "  peers: []", "    route: \"/wow/manifest\"")
    }

    @Test
    fun `the gate should fail without a baseline, pass on the recorded one, and fail on a breaking change`() {
        val project = project()
        val manifest = File(directory, "build/wow/manifest.json")
        val cart = ContextManifest("shop", errors = listOf(ErrorManifest("shop.cart.full", 409)))
        ManifestJson.write(Manifest(service = ServiceInfo("shop", "1.0.0"), contexts = listOf(cart)), manifest)
        val check = project.tasks.getByName(WowPlugin.CHECK_TASK) as CheckManifestCompatibility
        check.owner.get().assert().isEqualTo("project ':'")
        val update = project.tasks.getByName(WowPlugin.UPDATE_BASELINE_TASK) as UpdateManifestBaseline
        update.tools.from.assert().contains(project.configurations.getByName(WowPlugin.TOOLS_CONFIGURATION))
        val queue = inProcessQueue(project)

        assertThrows<IllegalStateException> { check.submit(queue) }.message.assert()
            .contains("project ':' has no Manifest baseline", "run updateWowManifestBaseline of project ':'")

        update.submit(queue)
        ManifestJson.read(File(directory, WowPlugin.BASELINE_FILE)).service.assert().isEqualTo(ServiceInfo("shop"))
        check.submit(queue)
        check.report.get().asFile.readText().assert().startsWith("Manifest against its baseline: 0 breaking")

        ManifestJson.write(
            Manifest(service = ServiceInfo("shop", "1.1.0"), contexts = listOf(cart.copyWith(errors = emptyList()))),
            manifest
        )
        val failure = assertThrows<IllegalStateException> { check.submit(queue) }
        failure.message.assert().contains("  - contexts[shop].errors[shop.cart.full]: error code removed")
    }

    @Test
    fun `a module's fragment should be named by its project path, one name per path`() {
        WowPlugin.fragmentName(":eshop:ordering-domain").assert().isEqualTo("eshop/ordering-domain")
        WowPlugin.fragmentName(":eshop-ordering-domain").assert().isEqualTo("eshop-ordering-domain")
        // Two paths that a `-` would join to one name, and a root project named as a child is.
        listOf(":a-b", ":a:b", ":a", ":").map(WowPlugin::fragmentName).assert()
            .containsExactly("a-b", "a/b", "a", WowPlugin.ROOT_FRAGMENT)
    }

    @Test
    fun `a service should be named by its project path by default`() {
        WowPlugin.defaultServiceName(":eshop:ordering-server", "root").assert().isEqualTo("eshop-ordering-server")
        WowPlugin.defaultServiceName(":", "root").assert().isEqualTo("root")
    }

    @Test
    fun `an old Gradle or Kotlin should fail, naming the project and both versions`() {
        assertThrows<GradleException> { WowPlugin.requireGradle(":shop", GradleVersion.version("8.14")) }
            .message.assert().contains(
                "Project ':shop'",
                "Gradle ${WowPlugin.MIN_GRADLE.version} or later",
                "Gradle 8.14"
            )
        WowPlugin.requireGradle(":shop", GradleVersion.version("9.0.1"))
        assertThrows<GradleException> { WowPlugin.requireKotlin(":shop", "1.9.25") }
            .message.assert().contains(
                "Project ':shop'",
                "Kotlin Gradle plugin 1.9.25",
                "Kotlin ${WowPlugin.minKotlin} or later"
            )
        assertThrows<GradleException> { WowPlugin.requireKotlin(":shop", "2.2.21") }
        WowPlugin.requireKotlin(":shop", WowPlugin.minKotlin + ".0")
        WowPlugin.requireKotlin(":shop", "3.0.0-Beta1")
    }

    @Test
    fun `the minimum Kotlin should be a real version for every Kotlin Wow can be built with`() {
        // One minor before; at an x.0 the version itself, never "3.-1"; a pre-release read past (#6879).
        WowPlugin.minimumKotlin("2.4.0").assert().isEqualTo("2.3")
        WowPlugin.minimumKotlin("3.0.0").assert().isEqualTo("3.0")
        WowPlugin.minimumKotlin("2.5.0-Beta1").assert().isEqualTo("2.4")
        WowPlugin.minimumKotlin("3.0.0-RC2").assert().isEqualTo("3.0")
        // The plugin's own: a real version, as requireKotlin compares and names it.
        WowPlugin.minKotlin.assert().matches("""\d+\.\d+""")
    }

    @Test
    fun `a module without KSP on its class path should be told the line that brings it, of the plugin's KSP`() {
        WowPlugin.kspVersion.assert().matches("""\d+\.\d+\.\d+.*""")
        WowPlugin.missingKsp(":shop-domain", WowPlugin.ID).assert().contains(
            "Project ':shop-domain' applies the Wow plugin (${WowPlugin.ID})",
            "id(\"${WowPlugin.API_ID}\") apply false",
            "Add id(\"com.google.devtools.ksp\") version \"${WowPlugin.kspVersion}\" to the module's plugins",
            "com.google.devtools.ksp:symbol-processing-gradle-plugin:${WowPlugin.kspVersion}",
        )
    }

    @Test
    fun `each source set's Kotlin compile and KSP should take its roots, relative to the project, as an input`() {
        // KSP is not on this class path, and the test compile's other inputs do not evaluate here:
        // SourceRootSwitchFunctionalTest shows every one of the four tasks takes the input.
        val domain = project(id = WowPlugin.ID)
        val roots = domain.tasks.getByName("compileKotlin").inputs.properties[SourceRoots.INPUT] as List<*>
        roots.assert().contains("src/main/kotlin")
        roots.forEach { it.toString().assert().doesNotStartWith("/") }
        val sourceSets = domain.extensions.getByType(SourceSetContainer::class.java)
        SourceRoots.rootsOf(domain, sourceSets.getByName("test")).assert().contains("src/test/kotlin")
        // Moved under another root, as a staged migration's sources are: the input moves with it.
        val main = sourceSets.getByName("main")
        (main.extensions.getByName("kotlin") as SourceDirectorySet).setSrcDirs(listOf("build/staged/main/kotlin"))
        main.java.setSrcDirs(listOf("build/staged/main/java"))
        // A Java root that moves alone moves the input too: both tasks read the Java sources.
        SourceRoots.rootsOf(domain, main).assert().containsExactly("build/staged/main/java", "build/staged/main/kotlin")
    }

    @Test
    fun `a domain module should build no service Manifest, a service module both`() {
        val domain = project(id = WowPlugin.ID)
        domain.tasks.findByName(WowPlugin.GENERATE_FRAGMENT_TASK).assert().isNotNull()
        domain.tasks.findByName(WowPlugin.GENERATE_CONTRACT_TASK).assert().isNotNull()
        listOf(
            WowPlugin.MERGE_TASK,
            WowPlugin.OPENAPI_TASK,
            WowPlugin.HELM_VALUES_TASK,
            WowPlugin.CHECK_TASK,
            WowPlugin.UPDATE_BASELINE_TASK,
        ).forEach { domain.tasks.findByName(it).assert().describedAs(it).isNull() }
        domain.extensions.getByType(WowExtension::class.java).assert().isNotInstanceOf(WowServiceExtension::class.java)
        val service = project()
        listOf(
            WowPlugin.GENERATE_FRAGMENT_TASK,
            WowPlugin.GENERATE_CONTRACT_TASK,
            WowPlugin.MERGE_TASK,
            WowPlugin.CHECK_TASK
        )
            .forEach { service.tasks.findByName(it).assert().describedAs(it).isNotNull() }
    }

    @Test
    fun `wow contract should require the contract feature of the api module it names`() {
        val project = project(id = WowPlugin.ID)
        val wow = project.extensions.getByType(WowExtension::class.java)
        val api = wow.contract(project.dependencies.project(mapOf("path" to project.path)))
        api.capabilitySelectors.single().displayName.assert().contains(ContractRole.FEATURE)
        val coordinates = wow.contract("sample:shop-api:1.0.0") as ExternalModuleDependency
        listOf(coordinates.group, coordinates.name, coordinates.version).assert()
            .containsExactly("sample", "shop-api", "1.0.0")
        coordinates.capabilitySelectors.single().displayName.assert().contains(ContractRole.FEATURE)
        val latest = wow.contract("sample:shop-api") as ExternalModuleDependency
        latest.version.assert().isNull()
        // An external module dependency, and a provider of one, as a version catalog's accessor is.
        val external = project.dependencies.create("sample:shop-api:1.0.0") as ExternalModuleDependency
        listOf(wow.contract(external), wow.contract(project.provider { external }).get()).forEach { contract ->
            (contract as ExternalModuleDependency).version.assert().isEqualTo("1.0.0")
            contract.capabilitySelectors.single().displayName.assert().contains(ContractRole.FEATURE)
        }
        val sources = project.dependencies.create("sample:shop-api:1.0.0:sources") as ExternalModuleDependency
        assertThrows<IllegalArgumentException> { wow.contract(sources) }.message.assert()
            .contains("not sample:shop-api, which names an artifact of a classifier or an extension")
        assertThrows<IllegalArgumentException> { wow.contract(42) }.message.assert()
            .contains("wow.contract(...) takes an api module", "not 42")
        listOf("sample:shop-api:1.0.0:sources", "sample:shop-api:1.0.0@jar", "shop-api").forEach { notation ->
            assertThrows<IllegalArgumentException> { wow.contract(notation) }.message.assert()
                .contains("\"group:name[:version]\"", "not \"$notation\", which is no plain group:name[:version]")
        }
    }

    @Test
    fun `a service of a types-only build should fail its merge naming the property`() {
        File(directory, "gradle.properties").writeText("${ContractRole.PUBLISH_PROPERTY}=false\n")
        val service = project()
        val merge = service.tasks.getByName(WowPlugin.MERGE_TASK)
        assertThrows<GradleException> { merge.actions.first().execute(merge) }.message.assert()
            .isEqualTo(ContractRole.typesOnlyService(service.path))
            .contains("-P${ContractRole.PUBLISH_PROPERTY}=false", "Build the service without it.")
    }
}
