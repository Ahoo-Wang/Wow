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
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerManifests
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.workers.WorkQueue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun `without the Kotlin plugin it should only add its extension`() {
        val project = project(kotlin = false)
        project.extensions.getByType(WowExtension::class.java).serviceName.get().assert().isEqualTo("shop")
        project.tasks.findByName(WowPlugin.GENERATE_FRAGMENT_TASK).assert().isNull()
        project.pluginManager.hasPlugin("com.google.devtools.ksp").assert().isFalse()
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
    fun `the peers and the manifest variant should be wired`() {
        val project = project()
        val peers = project.configurations.getByName(WowPlugin.PEERS_CONFIGURATION)
        peers.isCanBeConsumed.assert().isFalse()
        peers.isTransitive.assert().isFalse()
        val elements = project.configurations.getByName(WowPlugin.MANIFEST_ELEMENTS)
        elements.isCanBeConsumed.assert().isTrue()
        elements.isCanBeResolved.assert().isFalse()
        listOf(peers, elements).forEach { configuration ->
            configuration.attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE)?.name
                .assert().isEqualTo(WowPlugin.MANIFEST_DOCS_TYPE)
            configuration.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
                .assert().isEqualTo(Category.DOCUMENTATION)
        }
        elements.outgoing.artifacts.single().let {
            it.classifier.assert().isEqualTo(WowPlugin.MANIFEST_CLASSIFIER)
            it.extension.assert().isEqualTo("json")
            it.file.name.assert().isEqualTo("manifest.json")
        }
        val collect = project.tasks.getByName(WowPlugin.COLLECT_PEERS_TASK) as CollectPeerManifests
        collect.serviceName.get().assert().isEqualTo("shop")
        val output = File(directory, "build/wow/peers").canonicalFile
        collect.outputDirectory.get().asFile.canonicalFile.assert().isEqualTo(output)
        project.extensions.getByType(SourceSetContainer::class.java).getByName("main").output.dirs.files
            .map { it.canonicalFile }.assert().contains(output)
    }

    private fun peerFile(service: String?, vararg contexts: String): File =
        File(directory, "peers/${service ?: "fragment"}/manifest.json").also { file ->
            file.parentFile.mkdirs()
            ManifestJson.write(
                Manifest(service = service?.let { ServiceInfo(it) }, contexts = contexts.map { ContextManifest(it) }),
                file,
            )
        }

    /** A collect task of service `shop`, whose own fragment declares the context `shop`, in a directory and a jar. */
    private fun collectTask(vararg peers: File): CollectPeerManifests {
        val project = project()
        val classes = File(directory, "classes")
        ManifestJson.write(
            Manifest(contexts = listOf(ContextManifest("shop"))),
            File(classes, "${Manifest.FRAGMENT_DIRECTORY}/shop.json"),
        )
        val fragment = File(directory, "billing.json").also {
            ManifestJson.write(Manifest(contexts = listOf(ContextManifest("billing"))), it)
        }
        val jar = File(directory, "billing.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/"))
            zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/billing.json"))
            zip.write(fragment.readBytes())
            zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/nested/ignored.json"))
            zip.write("not json".toByteArray())
        }
        return (project.tasks.getByName(WowPlugin.COLLECT_PEERS_TASK) as CollectPeerManifests).apply {
            this.peers.setFrom(*peers)
            classpath.setFrom(classes, jar, File(directory, "missing"))
        }
    }

    @Test
    fun `collecting should copy each peer's manifest with an index`() {
        val inventory = peerFile("inventory", "inventory")
        val task = collectTask(inventory, peerFile("payment", "payment"))
        val output = File(directory, "build/wow/peers")
        File(output, "stale.txt").apply { parentFile.mkdirs() }.writeText("stale")

        task.collect()

        File(output, "stale.txt").exists().assert().isFalse()
        File(output, PeerManifests.resource("inventory")).readBytes().assert().isEqualTo(inventory.readBytes())
        File(output, PeerManifests.INDEX).readText().assert().isEqualTo("inventory.json\npayment.json\n")
    }

    @Test
    fun `collecting no peer should leave the output empty`() {
        val task = collectTask()
        task.collect()
        File(directory, "build/wow/peers").list().orEmpty().toList().assert().isEmpty()
    }

    @Test
    fun `collecting should fail on every conflict and every unreadable file`() {
        val malformed = File(directory, "malformed.json").apply { writeText("{") }
        val array = File(directory, "array.json").apply { writeText("[]") }
        val task = collectTask(
            peerFile("inventory", "inventory", "billing"),
            peerFile("warehouse", "inventory"),
            peerFile(null, "other"),
            malformed,
            array,
        )
        val failure = assertThrows<GradleException> { task.collect() }
        failure.message.assert().contains(
            "context billing is declared by service shop and by its peer inventory",
            "context inventory is declared by the peers inventory",
            "is not a service's merged Manifest",
            "malformed.json is not a Manifest",
            "array.json is not a JSON object",
        )
    }

    @Test
    fun `the manifest variant should be an optional variant of the java component`() {
        val project = project()
        val component = project.components.getByName("java") as SoftwareComponentInternal
        component.usages.map { it.name }.assert().contains(WowPlugin.MANIFEST_ELEMENTS)
    }

    @Test
    fun `collecting should fail on a fragment of the service that does not read`() {
        val task = collectTask()
        File(directory, "classes/${Manifest.FRAGMENT_DIRECTORY}/broken.json").writeText("{")
        val jar = File(directory, "broken.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/broken.json"))
            zip.write("[]".toByteArray())
            zip.putNextEntry(ZipEntry("${Manifest.FRAGMENT_DIRECTORY}/notes.txt"))
            zip.write("not a fragment".toByteArray())
            zip.putNextEntry(ZipEntry("other/shop.json"))
            zip.write("{".toByteArray())
        }
        val notes = File(directory, "notes.txt").apply { writeText("not a class path entry") }
        task.classpath.from(jar, notes)
        val failure = assertThrows<GradleException> { task.collect() }
        failure.message.assert().contains(
            "broken.json is not a Manifest",
            "broken.jar!/${Manifest.FRAGMENT_DIRECTORY}/broken.json is not a JSON object"
        )
        failure.message.assert().doesNotContain("notes.txt", "other/shop.json")
    }
}
