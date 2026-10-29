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

import com.google.devtools.ksp.gradle.KspExtension
import groovy.json.JsonSlurper
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException

/** The plugin's wiring, in process (ProjectBuilder); [WowPluginFunctionalTest] builds a real service with it. */
class WowPluginTest {
    @TempDir
    lateinit var directory: File

    /** A project of [id], the service plugin by default: the domain role on its sources, and the service's. */
    private fun project(kotlin: Boolean = true, id: String = WowPlugin.SERVICE_ID): Project = ProjectBuilder.builder()
        .withProjectDir(directory)
        .withName("shop")
        .build()
        .also { project ->
            project.version = "1.2.3"
            project.pluginManager.apply(id)
            if (kotlin) {
                project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            }
        }

    private fun ExternalModuleDependency.coordinates(): String = "$group:$name:$version"

    @Test
    fun `without the Kotlin plugin it should fail once the project is evaluated, naming the fix`() {
        val project = project(kotlin = false)
        project.pluginManager.apply("java")
        project.extensions.getByType(WowServiceExtension::class.java).service.isPresent.assert().isFalse()
        project.tasks.findByName(WowPlugin.GENERATE_FRAGMENT_TASK).assert().isNull()
        project.pluginManager.hasPlugin("com.google.devtools.ksp").assert().isFalse()
        val failure = assertThrows<ProjectConfigurationException> { (project as ProjectInternal).evaluate() }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message }.toList().assert()
            .contains(WowPlugin.missingKotlinJvm(":", WowPlugin.SERVICE_ID))
    }

    @Test
    fun `the Kotlin plugin applied after it should satisfy it`() {
        val project = project()
        (project as ProjectInternal).evaluate()
        project.tasks.findByName(WowPlugin.GENERATE_FRAGMENT_TASK).assert().isNotNull()
    }

    @Test
    fun `applied to an evaluated project without the Kotlin plugin it should fail at once`() {
        val project = ProjectBuilder.builder().withProjectDir(directory).withName("plain").build()
        (project as ProjectInternal).evaluate()
        val failure = assertThrows<Exception> { project.pluginManager.apply("me.ahoo.wow") }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message }.toList().assert()
            .contains(WowPlugin.missingKotlinJvm(":"))
    }

    @Test
    fun `it should apply KSP with the model processor alone`() {
        val project = project()
        project.pluginManager.hasPlugin("com.google.devtools.ksp").assert().isTrue()
        val ksp = project.extensions.getByType(KspExtension::class.java)
        ksp.arguments.assert()
            .containsEntry("wow.model", "generate")
            .containsEntry("wow.manifest.module", "shop")
    }

    @Test
    fun `the compiler and the tools should default to the plugin's version`() {
        val project = project()
        val compiler = project.configurations.getByName("ksp").incoming.dependencies
            .filterIsInstance<ExternalModuleDependency>().map { it.coordinates() }
        compiler.assert().containsExactly("me.ahoo.wow:wow-compiler:${WowPlugin.version}")
        val tools = project.configurations.getByName(WowPlugin.TOOLS_CONFIGURATION)
        tools.isCanBeConsumed.assert().isFalse()
        tools.incoming.dependencies.filterIsInstance<ExternalModuleDependency>().map { it.coordinates() }.assert()
            .containsExactly("me.ahoo.wow:wow-manifest-tools:${WowPlugin.version}")
    }

    @Test
    fun `a wow-compiler ksp declares should be the one it takes`() {
        val pinned = project()
        pinned.dependencies.add("ksp", "me.ahoo.wow:wow-compiler:10.0.0")
        pinned.configurations.getByName(
            "ksp"
        ).incoming.dependencies.map { it.name }.assert().containsExactly("wow-compiler")
        val vendored = project()
        vendored.dependencies.add("ksp", vendored.files("libs/wow-compiler-10.0.0.jar"))
        vendored.configurations.getByName("ksp").incoming.dependencies.filterIsInstance<ExternalModuleDependency>()
            .assert().isEmpty()
        // Another processor, Wow's or not, leaves it to the plugin.
        val other = project()
        other.dependencies.add("ksp", "sample:processor:1.0.0")
        other.dependencies.add("ksp", "me.ahoo.wow:wow-models:1.0.0")
        other.configurations.getByName("ksp").incoming.dependencies.map { it.name }.assert()
            .containsExactly("processor", "wow-models", "wow-compiler")
    }

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
    fun `a declared tools dependency should replace the default`() {
        val project = project()
        project.dependencies.add(WowPlugin.TOOLS_CONFIGURATION, project.files("tools.jar"))
        project.configurations.getByName(WowPlugin.TOOLS_CONFIGURATION).incoming.dependencies
            .filterIsInstance<ExternalModuleDependency>().assert().isEmpty()
    }

    @Test
    fun `the context argument should be passed only when set`() {
        val project = project()
        val context = project.objects.property(String::class.java)
        val argument = WowRoles.ContextArgument(context)
        argument.asArguments().toList().assert().isEmpty()
        context.set("shop")
        argument.asArguments().toList().assert().containsExactly("wow.context=shop")
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
    fun `the work should reach the tools by name and rethrow what they throw`() {
        val output = File(directory, "manifest.json")
        ManifestToolsWork.call(
            javaClass.classLoader,
            "merge",
            emptyList<File>(),
            emptyMap<String, String>(),
            "shop",
            "default",
            null,
            output
        )
            .assert().isEqualTo(0)
        ManifestJson.read(output).service?.name.assert().isEqualTo("shop")
        // The tools' own exception, not the reflective wrapper.
        assertThrows<FileNotFoundException> {
            ManifestToolsWork.call(
                javaClass.classLoader,
                "completeFragment",
                File(directory, "missing.json"),
                output,
                emptyMap<String, String>()
            )
        }
    }

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
    fun `the work actions should complete and merge fragments`() {
        val project = project()
        val skeleton = File(directory, "skeleton.json")
        ManifestJson.write(Manifest(), skeleton)
        val fragment = File(directory, "classes/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        val complete = project.objects.newInstance(ManifestToolsWork.CompleteFragment.Parameters::class.java).apply {
            this.fragment.set(skeleton)
            output.set(fragment)
            schemaOverrides.set(emptyMap())
        }
        object : ManifestToolsWork.CompleteFragment() {
            override fun getParameters() = complete
        }.execute()
        fragment.isFile.assert().isTrue()

        val manifest = File(directory, "manifest.json")
        val merge = project.objects.newInstance(ManifestToolsWork.Merge.Parameters::class.java).apply {
            classpath.from(File(directory, "classes"))
            defaultServiceName.set("shop")
            serviceVersion.set("1.0.0")
            output.set(manifest)
        }
        object : ManifestToolsWork.Merge() {
            override fun getParameters() = merge
        }.execute()
        ManifestJson.read(manifest).service.assert().isEqualTo(ServiceInfo("shop", "1.0.0"))
    }

    @Test
    fun `the contract variant should be wired, and no Manifest variant published`() {
        val project = project()
        val contract = project.configurations.getByName(WowPlugin.CONTRACT_ELEMENTS)
        contract.isCanBeConsumed.assert().isTrue()
        contract.isCanBeResolved.assert().isFalse()
        contract.attributes.getAttribute(
            DocsType.DOCS_TYPE_ATTRIBUTE
        )?.name.assert().isEqualTo(WowPlugin.CONTRACT_DOCS_TYPE)
        contract.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name.assert().isEqualTo(Category.DOCUMENTATION)
        contract.outgoing.artifacts.single().file.canonicalFile
            .assert().isEqualTo(File(directory, "build/wow/contract.json").canonicalFile)
        val generate = project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract
        generate.outputFile.get().asFile.name.assert().isEqualTo("contract.json")
        // The service's contract is its committed baseline: the java component offers no Manifest, nor the contract.
        project.configurations.findByName("wowManifestElements").assert().isNull()
        val component = project.components.getByName("java") as SoftwareComponentInternal
        component.usages.map { it.name }.assert().doesNotContain(WowPlugin.CONTRACT_ELEMENTS, "wowManifestElements")
    }

    @Test
    fun `generating should filter the completed fragments down to the contract`() {
        val project = project()
        val fragment = File(directory, "fragments/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        ManifestJson.write(Manifest(contexts = listOf(ContextManifest("shop"))), fragment)
        val task = (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).apply {
            fragments.setFrom(fragment)
            // The projects that do not pair with the module, as the build resolves them once the classes are built.
            unpaired.set(emptyMap())
        }

        task.submit(inProcessQueue(project))

        val contract = ManifestJson.read(File(directory, "build/wow/contract.json"))
        contract.service.assert().isNull()
        contract.contexts.map { it.name }.assert().containsExactly("shop")
    }

    @Test
    fun `a contract names the service its module declares`() {
        val project = project()
        project.extensions.getByType(WowExtension::class.java).service.set("shop-service")
        val fragment = File(directory, "fragments/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        ManifestJson.write(Manifest(contexts = listOf(ContextManifest("shop"))), fragment)
        (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).apply {
            fragments.setFrom(fragment)
            unpaired.set(emptyMap())
        }.submit(inProcessQueue(project))

        ManifestJson.read(File(directory, "build/wow/contract.json")).service.assert()
            .isEqualTo(ServiceInfo("shop-service"))
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
    fun `a module should be named by its project path`() {
        WowPlugin.moduleName(":eshop:ordering-domain", "root").assert().isEqualTo("eshop-ordering-domain")
        WowPlugin.moduleName(":shop", "root").assert().isEqualTo("shop")
        WowPlugin.moduleName(":", "root").assert().isEqualTo("root")
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
    fun `each role should add the BOM of the plugin's version and its Wow module, unless declared`() {
        fun declared(project: Project, configuration: String) =
            project.configurations.getByName(configuration).incoming.dependencies
                .filter { it.group == "me.ahoo.wow" }.map { "${it.group}:${it.name}:${it.version}" }
        val bom = "me.ahoo.wow:wow-bom:${WowPlugin.version}"
        declared(project(id = WowPlugin.ID), "implementation").assert()
            .containsExactlyInAnyOrder("me.ahoo.wow:wow-kernel:null", bom)
        val pinned = project(id = WowPlugin.ID)
        pinned.dependencies.add("implementation", "me.ahoo.wow:wow-kernel:10.0.0")
        declared(pinned, "implementation").assert().containsExactlyInAnyOrder("me.ahoo.wow:wow-kernel:10.0.0", bom)
        // Declared in another configuration the module compiles against, the module is the module's own.
        val implemented = project(id = WowPlugin.ID)
        implemented.dependencies.add("compileOnly", implemented.files("libs/wow-kernel-10.0.0.jar"))
        declared(implemented, "implementation").assert().containsExactly(bom)
    }

    @Test
    fun `the domain and service plugins should register the integration test suite, unless the build does`() {
        listOf(WowPlugin.ID, WowPlugin.SERVICE_ID).forEach { id ->
            val project = project(id = id)
            val suite = project.extensions.getByType(org.gradle.testing.base.TestingExtension::class.java).suites
                .getByName(WowPlugin.INTEGRATION_TEST)
            suite.assert().isInstanceOf(org.gradle.api.plugins.jvm.JvmTestSuite::class.java)
            project.configurations.getByName("integrationTestImplementation").extendsFrom.map { it.name }.assert()
                .contains("testImplementation")
            project.tasks.getByName("check").taskDependencies.getDependencies(null).map { it.name }.assert()
                .doesNotContain(WowPlugin.INTEGRATION_TEST)
        }
        val own = ProjectBuilder.builder().withProjectDir(File(directory, "own")).withName("own").build()
        own.pluginManager.apply("org.jetbrains.kotlin.jvm")
        own.extensions.getByType(org.gradle.testing.base.TestingExtension::class.java).suites
            .register(WowPlugin.INTEGRATION_TEST, org.gradle.api.plugins.jvm.JvmTestSuite::class.java) { it.useJUnit() }
        own.pluginManager.apply(WowPlugin.ID)
        own.configurations.getByName("integrationTestImplementation").extendsFrom.map { it.name }.assert()
            .doesNotContain("testImplementation")
        // A source set of that name the build creates itself, without a suite.
        val sourceSet = ProjectBuilder.builder().withProjectDir(File(directory, "set")).withName("set").build()
        sourceSet.pluginManager.apply("org.jetbrains.kotlin.jvm")
        sourceSet.extensions.getByType(SourceSetContainer::class.java).create(WowPlugin.INTEGRATION_TEST)
        sourceSet.pluginManager.apply(WowPlugin.ID)
        sourceSet.extensions.getByType(org.gradle.testing.base.TestingExtension::class.java).suites
            .findByName(WowPlugin.INTEGRATION_TEST).assert().isNull()
    }

    @Test
    fun `a domain module should build no service Manifest, a service module both`() {
        val domain = project(id = WowPlugin.ID)
        domain.tasks.findByName(WowPlugin.GENERATE_FRAGMENT_TASK).assert().isNotNull()
        domain.tasks.findByName(WowPlugin.GENERATE_CONTRACT_TASK).assert().isNotNull()
        listOf(WowPlugin.MERGE_TASK, WowPlugin.OPENAPI_TASK, WowPlugin.CHECK_TASK, WowPlugin.UPDATE_BASELINE_TASK)
            .forEach { domain.tasks.findByName(it).assert().describedAs(it).isNull() }
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
    fun `a context whose classes are in a project that does not pair should fail, naming the line to add`() {
        val project = project(id = WowPlugin.ID)
        val fragment = File(directory, "fragments/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        val aggregate = AggregateManifest(
            name = "cart",
            state = StateManifest(Manifest.schemaRef("sample.CartState")),
            commands = listOf(CommandManifest("AddItem", schema = Manifest.schemaRef("sample.AddItem"))),
        )
        ManifestJson.write(
            Manifest(
                contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate))),
                defs = mapOf("sample.CartState" to mapOf("type" to "object")),
            ),
            fragment,
        )
        val apiClasses = File(directory, "shop-api/build/classes/kotlin/main")
        File(apiClasses, "sample/AddItem.class").apply { parentFile.mkdirs() }.writeText("")
        val task = (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).apply {
            fragments.setFrom(fragment)
            unpaired.set(mapOf(apiClasses.path to ":shop-api", File(directory, "missing").path to ":other"))
        }
        val queue = inProcessQueue(project)
        assertThrows<IllegalStateException> { task.submit(queue) }.message.assert().contains(
            "The contract of project ':' reaches no api module",
            "context shop has its classes sample.AddItem in project ':shop-api', which does not pair with project ':'",
            "apply id(\"me.ahoo.wow.api\") and add dependencies { wowContract(project(\":\")) }",
        )
        // Paired, the api's classes are a core view, which is no unpaired entry.
        task.unpaired.set(emptyMap())
        task.submit(queue)
        ManifestJson.read(
            File(directory, "build/wow/contract.json")
        ).contexts.map { it.name }.assert().containsExactly("shop")
    }
}
