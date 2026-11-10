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
import me.ahoo.wow.manifest.Manifest
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The service role's wiring ([ServiceRole]): the merge class path, its tasks, the version, the compatibility gate and the integration suite. */
class ServiceRoleTest {
    @TempDir
    lateinit var directory: File

    /** A project of [id], the service plugin by default: the domain role on its sources, and the service's. */
    private fun project(kotlin: Boolean = true, id: String = WowPlugin.SERVICE_ID): Project =
        wowProject(directory, kotlin, id)

    @Test
    fun `a failure should name the owner of each class path entry, a project, a module or a file`() {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("store").build()
        // A project whose artifact no task builds, so its owner resolves here, as the merge reads it once built.
        val lib = ProjectBuilder.builder().withParent(
            root
        ).withName("lib").withProjectDir(File(directory, "lib")).build()
        val libJar = File(directory, "lib/lib.jar")
        lib.configurations.create("elements") { it.isCanBeResolved = false }
        lib.artifacts.add("elements", libJar)
        val repository = File(directory, "maven")
        File(repository, "com/acme/billing/1.0.0/billing-1.0.0.pom").apply { parentFile.mkdirs() }.writeText(
            """<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>""" +
                """<artifactId>billing</artifactId><version>1.0.0</version></project>""",
        )
        File(repository, "com/acme/billing/1.0.0/billing-1.0.0.jar").writeText("")
        // The BOM and the kernel the plugin adds.
        val version = WowPlugin.version
        mapOf("wow-bom" to "pom", "wow-kernel" to "jar").forEach { (artifact, packaging) ->
            val directory = File(repository, "me/ahoo/wow/$artifact/$version").apply { mkdirs() }
            File(directory, "$artifact-$version.pom").writeText(
                "<project><modelVersion>4.0.0</modelVersion><groupId>me.ahoo.wow</groupId><artifactId>$artifact" +
                    "</artifactId><version>$version</version><packaging>$packaging</packaging></project>",
            )
            if (packaging == "jar") File(directory, "$artifact-$version.jar").writeText("")
        }
        val vendor = File(directory, "shop/vendor/tool.jar").apply { parentFile.mkdirs() }.also { it.writeText("") }
        val shop = ProjectBuilder.builder().withParent(root).withName("shop").withProjectDir(File(directory, "shop"))
            .build()
        shop.extensions.extraProperties.set("kotlin.stdlib.default.dependency", "false")
        shop.pluginManager.apply(WowPlugin.SERVICE_ID)
        shop.pluginManager.apply("org.jetbrains.kotlin.jvm")
        shop.repositories.maven { it.url = repository.toURI() }
        shop.dependencies.add(
            "implementation",
            shop.dependencies.project(mapOf("path" to ":lib", "configuration" to "elements"))
        )
        shop.dependencies.add("implementation", "com.acme:billing:1.0.0")
        shop.dependencies.add("implementation", "me.ahoo.wow:wow-kernel:${WowPlugin.version}")
        shop.dependencies.add("implementation", shop.files(vendor))
        (shop as ProjectInternal).evaluate()
        val owners = (shop.tasks.getByName(WowPlugin.MERGE_TASK) as MergeManifest).owners.get()
        owners.assert()
            .containsEntry(libJar.path, "project ':lib'")
            .containsEntry(
                File(shop.layout.buildDirectory.get().asFile, "wow/manifest-fragment").path,
                "project ':shop'"
            )
            .containsKey(vendor.path)
        owners.values.assert().contains("com.acme:billing:1.0.0")
    }

    @Test
    fun `the tasks should be wired to the extension and the main source set`() {
        val project = project()
        val extension = project.extensions.getByType(WowServiceExtension::class.java)
        extension.schemaOverrides.put("a.Money", """{"type":"string"}""")
        project.version = "2.0.0"

        val generate = project.tasks.getByName(WowPlugin.GENERATE_FRAGMENT_TASK) as GenerateManifestFragment
        // The wow group holds the tasks a user runs; the plumbing has none.
        generate.group.assert().isNull()
        project.tasks.filter { it.group == "wow" }.map { it.name }.assert().containsExactlyInAnyOrder(
            WowPlugin.MERGE_TASK,
            WowPlugin.OPENAPI_TASK,
            WowPlugin.HELM_VALUES_TASK,
            WowPlugin.UPDATE_BASELINE_TASK,
        )
        generate.schemaOverrides.get().assert().containsEntry("a.Money", """{"type":"string"}""")
        val fragments = File(project.projectDir, "build/wow/manifest-fragment")
        generate.outputDirectory.get().asFile.assert().isEqualTo(fragments)
        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        main.output.dirs.files.assert().contains(fragments)
        // The completed fragment takes the place of the compiler's.
        (project.tasks.getByName("processResources") as ProcessResources).excludes.assert()
            .contains("${Manifest.FRAGMENT_DIRECTORY}/**")

        val merge = project.tasks.getByName(WowPlugin.MERGE_TASK) as MergeManifest
        // Unset, the merge derives the name from the contracts, the project path's when none names one.
        merge.serviceName.isPresent.assert().isFalse()
        merge.defaultServiceName.get().assert().isEqualTo("shop")
        // The module's own contexts name their service once, for its contract and its Manifest alike.
        extension.service.set("shop-service")
        merge.serviceName.get().assert().isEqualTo("shop-service")
        (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).service.get().assert()
            .isEqualTo("shop-service")
        merge.serviceVersion.get().assert().isEqualTo("2.0.0")
        merge.outputFile.get().asFile.assert().isEqualTo(File(project.projectDir, "build/wow/manifest.json"))
    }

    @Test
    fun `the service version should default to the project's`() {
        (project().tasks.getByName(WowPlugin.MERGE_TASK) as MergeManifest).serviceVersion.get().assert()
            .isEqualTo("1.2.3")
    }

    @Test
    fun `the compatibility gate should be part of check and read the baseline`() {
        val project = project()
        val check = project.tasks.getByName(WowPlugin.CHECK_TASK) as CheckManifestCompatibility
        fun File.canonical() = canonicalFile
        check.baseline.get().asFile.canonical().assert().isEqualTo(File(directory, WowPlugin.BASELINE_FILE).canonical())
        check.manifest.get().asFile.canonical().assert().isEqualTo(
            File(directory, "build/wow/manifest.json").canonical()
        )
        check.report.get().asFile.name.assert().isEqualTo("manifest-compatibility.txt")
        project.tasks.getByName("check").taskDependencies.getDependencies(null).assert().contains(check)
        val update = project.tasks.getByName(WowPlugin.UPDATE_BASELINE_TASK) as UpdateManifestBaseline
        update.baseline.get().asFile.canonical().assert().isEqualTo(
            File(directory, WowPlugin.BASELINE_FILE).canonical()
        )

        project.extensions.getByType(WowServiceExtension::class.java).manifestBaseline
            .set(File(directory, "contract.json"))
        check.baseline.get().asFile.name.assert().isEqualTo("contract.json")
        update.baseline.get().asFile.name.assert().isEqualTo("contract.json")
    }

    @Test
    fun `the service plugin should register the integration test suite, unless the build does`() {
        val service = project(id = WowPlugin.SERVICE_ID)
        val suite = service.extensions.getByType(org.gradle.testing.base.TestingExtension::class.java).suites
            .getByName(WowPlugin.INTEGRATION_TEST)
        suite.assert().isInstanceOf(org.gradle.api.plugins.jvm.JvmTestSuite::class.java)
        service.configurations.getByName("integrationTestImplementation").extendsFrom.map { it.name }.assert()
            .contains("testImplementation")
        service.configurations.getByName("integrationTestRuntimeOnly").extendsFrom.map { it.name }.assert()
            .contains("testRuntimeOnly")
        service.tasks.getByName("check").taskDependencies.getDependencies(null).map { it.name }.assert()
            .doesNotContain(WowPlugin.INTEGRATION_TEST)
        // A domain module is pure: container tests belong to the service that wires the stores.
        project(id = WowPlugin.ID).extensions.getByType(org.gradle.testing.base.TestingExtension::class.java).suites
            .findByName(WowPlugin.INTEGRATION_TEST).assert().isNull()
        val own = ProjectBuilder.builder().withProjectDir(File(directory, "own")).withName("own").build()
        own.pluginManager.apply("org.jetbrains.kotlin.jvm")
        own.extensions.getByType(org.gradle.testing.base.TestingExtension::class.java).suites
            .register(WowPlugin.INTEGRATION_TEST, org.gradle.api.plugins.jvm.JvmTestSuite::class.java) { it.useJUnit() }
        own.pluginManager.apply(WowPlugin.SERVICE_ID)
        own.configurations.getByName("integrationTestImplementation").extendsFrom.map { it.name }.assert()
            .doesNotContain("testImplementation")
        // A source set of that name the build creates itself, without a suite.
        val sourceSet = ProjectBuilder.builder().withProjectDir(File(directory, "set")).withName("set").build()
        sourceSet.pluginManager.apply("org.jetbrains.kotlin.jvm")
        sourceSet.extensions.getByType(SourceSetContainer::class.java).create(WowPlugin.INTEGRATION_TEST)
        sourceSet.pluginManager.apply(WowPlugin.SERVICE_ID)
        sourceSet.extensions.getByType(org.gradle.testing.base.TestingExtension::class.java).suites
            .findByName(WowPlugin.INTEGRATION_TEST).assert().isNull()
    }
}
