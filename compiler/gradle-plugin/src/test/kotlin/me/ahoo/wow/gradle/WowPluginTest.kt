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
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.workers.WorkQueue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException

/** The plugin's wiring, in process (ProjectBuilder); [WowPluginFunctionalTest] builds a real service with it. */
class WowPluginTest {
    @TempDir
    lateinit var directory: File

    private fun project(kotlin: Boolean = true): Project = ProjectBuilder.builder()
        .withProjectDir(directory)
        .withName("shop")
        .build()
        .also { project ->
            project.version = "1.2.3"
            project.pluginManager.apply("me.ahoo.wow")
            if (kotlin) {
                project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            }
        }

    private fun ExternalModuleDependency.coordinates(): String = "$group:$name:$version"

    @Test
    fun `without the Kotlin plugin it should fail once the project is evaluated, naming the fix`() {
        val project = project(kotlin = false)
        project.pluginManager.apply("java")
        project.extensions.getByType(WowExtension::class.java).serviceName.get().assert().isEqualTo("shop")
        project.tasks.findByName(WowPlugin.GENERATE_FRAGMENT_TASK).assert().isNull()
        project.pluginManager.hasPlugin("com.google.devtools.ksp").assert().isFalse()
        val failure = assertThrows<ProjectConfigurationException> { (project as ProjectInternal).evaluate() }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message }.toList().assert()
            .contains(WowPlugin.missingKotlinJvm(":"))
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
    fun `turning the compiler dependency off should leave ksp to the build`() {
        val project = project()
        project.extensions.getByType(WowExtension::class.java).addCompilerDependency.set(false)
        project.configurations.getByName("ksp").incoming.dependencies.assert().isEmpty()
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
        val argument = WowPlugin.ContextArgument(context)
        argument.asArguments().toList().assert().isEmpty()
        context.set("shop")
        argument.asArguments().toList().assert().containsExactly("wow.context=shop")
    }

    @Test
    fun `the tasks should be wired to the extension and the main source set`() {
        val project = project()
        val extension = project.extensions.getByType(WowExtension::class.java)
        extension.schemaOverrides.put("a.Money", """{"type":"string"}""")
        extension.serviceVersion.set("2.0.0")

        val generate = project.tasks.getByName(WowPlugin.GENERATE_FRAGMENT_TASK) as GenerateManifestFragment
        generate.group.assert().isEqualTo("wow")
        generate.schemaOverrides.get().assert().containsEntry("a.Money", """{"type":"string"}""")
        val fragments = File(project.projectDir, "build/wow/manifest-fragment")
        generate.outputDirectory.get().asFile.assert().isEqualTo(fragments)
        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        main.output.dirs.files.assert().contains(fragments)
        // The completed fragment takes the place of the compiler's.
        (project.tasks.getByName("processResources") as ProcessResources).excludes.assert()
            .contains("${Manifest.FRAGMENT_DIRECTORY}/**")

        val merge = project.tasks.getByName(WowPlugin.MERGE_TASK) as MergeManifest
        merge.serviceName.get().assert().isEqualTo("shop")
        merge.serviceVersion.get().assert().isEqualTo("2.0.0")
        merge.outputFile.get().asFile.assert().isEqualTo(File(project.projectDir, "build/wow/manifest.json"))
    }

    @Test
    fun `the service version should default to the project's`() {
        project().extensions.getByType(WowExtension::class.java).serviceVersion.get().assert().isEqualTo("1.2.3")
    }

    @Test
    fun `the work should reach the tools by name and rethrow what they throw`() {
        val output = File(directory, "manifest.json")
        ManifestToolsWork.call(javaClass.classLoader, "merge", emptyList<File>(), "shop", null, output)
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

    /** A work queue that runs each item in process, as the worker would. */
    private fun inProcessQueue(project: Project): WorkQueue {
        val queue = mockk<WorkQueue>()
        every { queue.submit(ManifestToolsWork.CompleteFragment::class.java, any()) } answers {
            val parameters = project.objects.newInstance(ManifestToolsWork.CompleteFragment.Parameters::class.java)
            secondArg<Action<ManifestToolsWork.CompleteFragment.Parameters>>().execute(parameters)
            object : ManifestToolsWork.CompleteFragment() {
                override fun getParameters() = parameters
            }.execute()
        }
        every { queue.submit(ManifestToolsWork.Merge::class.java, any()) } answers {
            val parameters = project.objects.newInstance(ManifestToolsWork.Merge.Parameters::class.java)
            secondArg<Action<ManifestToolsWork.Merge.Parameters>>().execute(parameters)
            object : ManifestToolsWork.Merge() {
                override fun getParameters() = parameters
            }.execute()
        }
        return queue
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

        openApi.generate()

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
            serviceName.set("shop")
            serviceVersion.set("1.0.0")
            output.set(manifest)
        }
        object : ManifestToolsWork.Merge() {
            override fun getParameters() = merge
        }.execute()
        ManifestJson.read(manifest).service?.version.assert().isEqualTo("1.0.0")
    }

    @Test
    fun `the manifest and contract variants should be wired`() {
        val project = project()
        val elements = project.configurations.getByName(WowPlugin.MANIFEST_ELEMENTS)
        val contract = project.configurations.getByName(WowPlugin.CONTRACT_ELEMENTS)
        mapOf(elements to WowPlugin.MANIFEST_DOCS_TYPE, contract to WowPlugin.CONTRACT_DOCS_TYPE)
            .forEach { (configuration, docsType) ->
                configuration.isCanBeConsumed.assert().isTrue()
                configuration.isCanBeResolved.assert().isFalse()
                configuration.attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE)?.name.assert().isEqualTo(docsType)
                configuration.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
                    .assert().isEqualTo(Category.DOCUMENTATION)
            }
        elements.outgoing.artifacts.single().let {
            it.classifier.assert().isEqualTo(WowPlugin.MANIFEST_CLASSIFIER)
            it.extension.assert().isEqualTo("json")
            it.file.name.assert().isEqualTo("manifest.json")
        }
        contract.outgoing.artifacts.single().file.canonicalFile
            .assert().isEqualTo(File(directory, "build/wow/contract.json").canonicalFile)
        val generate = project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract
        generate.outputFile.get().asFile.name.assert().isEqualTo("contract.json")
    }

    @Test
    fun `the manifest variant should be an optional variant of the java component`() {
        val project = project()
        val component = project.components.getByName("java") as SoftwareComponentInternal
        component.usages.map { it.name }.assert().contains(WowPlugin.MANIFEST_ELEMENTS)
        component.usages.map { it.name }.assert().doesNotContain(WowPlugin.CONTRACT_ELEMENTS)
    }

    /** A build of `:shop-api` (plain Kotlin) and the domain modules [domains], each declaring it in `api(...)`. */
    private fun paired(vararg domains: String): Project {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = ProjectBuilder.builder().withParent(root).withName("shop-api")
            .withProjectDir(File(directory, "shop-api")).build()
        api.pluginManager.apply("org.jetbrains.kotlin.jvm")
        domains.forEach { name ->
            val domain = ProjectBuilder.builder().withParent(root).withName(name)
                .withProjectDir(File(directory, name)).build()
            domain.pluginManager.apply("me.ahoo.wow")
            domain.pluginManager.apply("org.jetbrains.kotlin.jvm")
            domain.dependencies.add("api", domain.dependencies.project(mapOf("path" to ":shop-api")))
            domain.dependencies.add("implementation", "sample:library:1.0.0")
        }
        (api as ProjectInternal).evaluate()
        return api
    }

    @Test
    fun `an api project of a domain module should carry its contract`() {
        val api = paired("shop-domain", "audit-domain")
        val contracts = api.configurations.getByName(WowPlugin.CONTRACT_CONFIGURATION)
        contracts.isCanBeConsumed.assert().isFalse()
        contracts.isTransitive.assert().isFalse()
        contracts.attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE)?.name
            .assert().isEqualTo(WowPlugin.CONTRACT_DOCS_TYPE)
        contracts.dependencies.map { it.name }.assert().containsExactlyInAnyOrder("shop-domain", "audit-domain")
        val assemble = api.tasks.getByName(WowPlugin.ASSEMBLE_CONTRACT_TASK) as AssembleContract
        assemble.owner.get().assert().isEqualTo(":shop-api")
        assemble.outputDirectory.get().asFile.canonicalFile
            .assert().isEqualTo(File(directory, "shop-api/build/wow/contract").canonicalFile)
        (api.tasks.getByName("jar") as Jar).taskDependencies.getDependencies(null).map { it.name }
            .assert().contains(WowPlugin.ASSEMBLE_CONTRACT_TASK)
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
        (api.tasks.getByName("jar") as Jar).taskDependencies.getDependencies(null).map { it.name }
            .assert().contains("compileWowContractKotlin")
        val wowContractClasses = File(directory, "shop-api/build/classes/kotlin/wowContract").canonicalFile
        listOf("apiElements", "runtimeElements").forEach { name ->
            val variants = api.configurations.getByName(name).outgoing.variants
            variants.getByName("classes").artifacts.map { it.file.canonicalFile }.assert().contains(wowContractClasses)
            // One core view per paired domain module, without the generated classes.
            val cores = variants.filter { it.attributes.contains(WowPlugin.API_VIEW) }
            cores.map { it.attributes.getAttribute(WowPlugin.API_VIEW) }
                .assert().containsExactlyInAnyOrder(
                    WowPlugin.coreView(":shop-domain"),
                    WowPlugin.coreView(":audit-domain")
                )
            cores.flatMap { it.artifacts }.map { it.file.canonicalFile }.assert().doesNotContain(wowContractClasses)
        }
        // The domain module compiles against its core view.
        api.rootProject.project(":shop-domain").configurations.getByName("compileClasspath").attributes
            .getAttribute(WowPlugin.API_VIEW).assert().isEqualTo(WowPlugin.coreView(":shop-domain"))
    }

    @Test
    fun `generating the contract sources should write one object per context`() {
        val api = paired("shop-domain")
        val task = api.tasks.getByName(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK) as GenerateContractSources
        val output = task.outputDirectory.get().asFile
        File(output, "stale.kt").apply { parentFile.mkdirs() }.writeText("stale")
        task.contract.setFrom(contractFile("shop-cart", "sample.AddItem"), File(directory, "missing.json"))

        task.generate()

        File(output, "stale.kt").exists().assert().isFalse()
        File(output, "sample/ShopCartContract.kt").readText().assert().contains(
            "package sample",
            "object ShopCartContract",
            "const val NAME: String = \"shop-cart\"",
            "object Cart",
            "AggregateType(NAME, \"cart\")",
            "fun ref(tenantId: String, id: String): AggregateRef = TYPE.ref(tenantId, id)",
        )
    }

    @Test
    fun `an api project without the Kotlin plugin should carry no generated code`() {
        val root = ProjectBuilder.builder().withProjectDir(directory).withName("services").build()
        val api = ProjectBuilder.builder().withParent(root).withName("java-api")
            .withProjectDir(File(directory, "java-api")).build()
        api.pluginManager.apply("java")
        val domain = ProjectBuilder.builder().withParent(root).withName("domain")
            .withProjectDir(File(directory, "domain")).build()
        domain.pluginManager.apply("me.ahoo.wow")
        domain.pluginManager.apply("org.jetbrains.kotlin.jvm")
        domain.dependencies.add("api", domain.dependencies.project(mapOf("path" to ":java-api")))
        (api as ProjectInternal).evaluate()
        api.tasks.findByName(WowPlugin.ASSEMBLE_CONTRACT_TASK).assert().isNotNull()
        api.tasks.findByName(WowPlugin.GENERATE_CONTRACT_SOURCES_TASK).assert().isNull()
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
            this.classes.setFrom(classes, File(directory, "missing"))
        }
    }

    @Test
    fun `assembling should keep the contexts whose classes are the api module's`() {
        val task = assembleTask(contractFile("shop", "sample.AddItem"), contractFile("billing", "sample.Pay"))
        val output = File(directory, "shop-api/build/wow/contract")
        File(output, "stale.txt").apply { parentFile.mkdirs() }.writeText("stale")

        task.assemble()

        File(output, "stale.txt").exists().assert().isFalse()
        val contract = ManifestJson.read(File(output, PeerContracts.RESOURCE))
        contract.contexts.map { it.name }.assert().containsExactly("shop")
        contract.defs.keys.assert().containsExactly("sample.CartState")
    }

    @Test
    fun `assembling no context of the api module should write no contract`() {
        val task = assembleTask(contractFile("billing", "sample.Pay"))
        task.assemble()
        File(directory, "shop-api/build/wow/contract").list().orEmpty().toList().assert().isEmpty()
    }

    @Test
    fun `assembling a context with classes outside the api module should fail naming them`() {
        val task = assembleTask(contractFile("shop", "sample.AddItem", "sample.RemoveItem"))
        assertThrows<GradleException> { task.assemble() }.message.assert()
            .contains("context shop of ", "has classes that are not in :shop-api: sample.RemoveItem")
    }

    @Test
    fun `generating should filter the completed fragments down to the contract`() {
        val project = project()
        val fragment = File(directory, "fragments/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        ManifestJson.write(Manifest(contexts = listOf(ContextManifest("shop"))), fragment)
        val task = (project.tasks.getByName(WowPlugin.GENERATE_CONTRACT_TASK) as GenerateContract).apply {
            fragments.setFrom(fragment)
        }

        task.generate()

        val contract = ManifestJson.read(File(directory, "build/wow/contract.json"))
        contract.service.assert().isNull()
        contract.contexts.map { it.name }.assert().containsExactly("shop")
    }

    @Test
    fun `the compatibility gate should be part of check and read the baseline`() {
        val project = project()
        val check = project.tasks.getByName(WowPlugin.CHECK_COMPATIBILITY_TASK) as CheckManifestCompatibility
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

        project.extensions.getByType(WowExtension::class.java).manifestBaseline.set(File(directory, "contract.json"))
        check.baseline.get().asFile.name.assert().isEqualTo("contract.json")
        update.baseline.get().asFile.name.assert().isEqualTo("contract.json")
    }

    @Test
    fun `the gate should pass without a baseline, record one, and fail on a breaking change`() {
        val project = project()
        val manifest = File(directory, "build/wow/manifest.json")
        val cart = ContextManifest("shop", errors = listOf(ErrorManifest("shop.cart.full", 409)))
        ManifestJson.write(Manifest(service = ServiceInfo("shop", "1.0.0"), contexts = listOf(cart)), manifest)
        val check = project.tasks.getByName(WowPlugin.CHECK_COMPATIBILITY_TASK) as CheckManifestCompatibility
        val queue = mockk<WorkQueue>()
        every { queue.submit(ManifestToolsWork.CheckCompatibility::class.java, any()) } answers {
            val parameters = project.objects.newInstance(ManifestToolsWork.CheckCompatibility.Parameters::class.java)
            secondArg<Action<ManifestToolsWork.CheckCompatibility.Parameters>>().execute(parameters)
            object : ManifestToolsWork.CheckCompatibility() {
                override fun getParameters() = parameters
            }.execute()
        }

        check.submit(queue)
        check.report.get().asFile.readText().assert().startsWith("No Manifest baseline")

        (project.tasks.getByName(WowPlugin.UPDATE_BASELINE_TASK) as UpdateManifestBaseline).update()
        File(directory, WowPlugin.BASELINE_FILE).readBytes().assert().isEqualTo(manifest.readBytes())
        check.submit(queue)
        check.report.get().asFile.readText().assert().startsWith(
            "Manifest 1.0.0 against the baseline 1.0.0: 0 breaking"
        )

        ManifestJson.write(
            Manifest(service = ServiceInfo("shop", "1.1.0"), contexts = listOf(cart.copyWith(errors = emptyList()))),
            manifest
        )
        val failure = assertThrows<IllegalStateException> { check.submit(queue) }
        failure.message.assert().contains("  - contexts[shop].errors[shop.cart.full]: error code removed")
    }
}
