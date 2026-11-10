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
        ApiRole.coreVariant(":a-b").assert().isNotEqualTo(ApiRole.coreVariant(":a:b"))
        ApiRole.coreVariant(":eshop:ordering-domain").assert().isEqualTo("wowCore:eshop:ordering-domain")
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
}
