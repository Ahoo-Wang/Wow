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

import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.process.JavaForkOptions
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.workers.ProcessWorkerSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The domain role's wiring ([DomainRole]): the Kotlin it needs, KSP and the compiler, the context argument, the contract variant. */
class DomainRoleTest {
    @TempDir
    lateinit var directory: File

    /** A project of [id], the service plugin by default: the domain role on its sources, and the service's. */
    private fun project(kotlin: Boolean = true, id: String = WowPlugin.SERVICE_ID): Project =
        wowProject(directory, kotlin, id)

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
    fun `the fragment task should run on the Java toolchain of the module, not on the JVM that runs Gradle`() {
        val project = project()
        // The toolchain is declared after the plugin is applied, as `kotlin { jvmToolchain(21) }` does in a build file.
        val toolchain = project.extensions.getByType(JavaPluginExtension::class.java).toolchain
        toolchain.languageVersion.set(JavaLanguageVersion.of(TOOLCHAIN))
        val registered = project.tasks.getByName(WowPlugin.GENERATE_FRAGMENT_TASK) as GenerateManifestFragment
        // A task of its own with the registered one's launcher: its class path resolves the module's dependencies.
        val task = project.tasks.register("fragmentProbe", GenerateManifestFragment::class.java).get()
        task.launcher.set(registered.launcher)
        val classes = File(directory, "classes")
        val tools = File(directory, "tools.jar")
        task.classpath.from(classes)
        task.tools.from(tools)

        val classpath = project.files()
        val fork = mockk<JavaForkOptions>(relaxed = true)
        val spec = mockk<ProcessWorkerSpec> {
            every { this@mockk.classpath } returns classpath
            every { forkOptions } returns fork
        }
        task.isolate(spec)

        val launcher = task.launcher.get()
        launcher.metadata.languageVersion.asInt().assert().isEqualTo(TOOLCHAIN)
        val jdk = requireNotNull(System.getProperty("wow-test.jdk.daemon"))
        launcher.executablePath.asFile.path.assert().startsWith(jdk)
        verify { fork.executable(launcher.executablePath.asFile.absolutePath) }
        // The module's classes before the tools, which read them with the Jackson the module runs with.
        classpath.files.toList().assert().isEqualTo(listOf(classes, tools))
    }

    @Test
    fun `the fragment task's action should open a worker process queue and submit its fragments to it`() {
        val project = project()
        val task = project.tasks.getByName(WowPlugin.GENERATE_FRAGMENT_TASK) as GenerateManifestFragment
        val stale = File(directory, "build/wow/manifest-fragment/${Manifest.FRAGMENT_DIRECTORY}/stale.json")
        stale.apply { parentFile.mkdirs() }.writeText("{}")

        // No fragment, so no worker is started; the queue is Gradle's own, which takes the isolation as configured.
        task.generate()

        stale.exists().assert().isFalse()
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
            .containsEntry("wow.manifest.module", WowPlugin.ROOT_FRAGMENT)
    }

    @Test
    fun `its KSP tasks should use no build service that runs them one at a time`() {
        val project = project()
        (project as ProjectInternal).evaluate()
        val serial = project.gradle.sharedServices.registrations.filter { it.maxParallelUsages.orNull == 1 }
        project.tasks.withType(KspAATask::class.java).toList().assert().isNotEmpty().allSatisfy { task ->
            serial.none { (task as TaskInternal).requiredServices.isServiceRequired(it.service) }.assert().isTrue()
        }
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
            .containsExactlyInAnyOrder("processor", "wow-models", "wow-compiler")
    }

    @Test
    fun `the context argument should be passed only when set`() {
        val project = project()
        val context = project.objects.property(String::class.java)
        val argument = DomainRole.ContextArgument(context)
        argument.asArguments().toList().assert().isEmpty()
        context.set("shop")
        argument.asArguments().toList().assert().containsExactly("wow.context=shop")
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

    /** A class Gradle decorates when it creates an instance, as it does a plugin's extension. */
    open class DecoratedArgs

    /** An extension of a KSP whose `arg` takes a key and a value only. */
    class KeyValueArgs {
        val args = mutableListOf<Pair<String, String>>()

        fun arg(key: String, value: String) {
            args += key to value
        }
    }

    @Test
    fun `a KSP argument should be passed by the extension's arg, whatever class loader holds it`() {
        val ksp = KeyValueArgs()
        DomainRole.kspArg(ksp, "wow.model", "generate")
        ksp.args.assert().containsExactly("wow.model" to "generate")
    }

    @Test
    fun `a KSP without the arg the plugin calls should fail naming its version and the plugin's`() {
        assertThrows<GradleException> {
            DomainRole.kspArg(KeyValueArgs(), DomainRole.ContextArgument(project().provider { "bank" }))
        }.message.assert().contains(
            "The KSP Gradle plugin of an unknown version has no ksp { arg(CommandLineArgumentProvider) }",
            "the plugin is built for KSP ${WowPlugin.kspVersion}",
            "id(\"com.google.devtools.ksp\") version \"${WowPlugin.kspVersion}\"",
        )
        DomainRole.kspSignatureMissing("9.9.9", listOf("String", "String")).assert()
            .startsWith("The KSP Gradle plugin 9.9.9 has no ksp { arg(String, String) }")
    }

    @Test
    fun `a module whose class path holds no KSP should fail naming the line to add`() {
        assertThrows<GradleException> { DomainRole.applyKsp(project(), WowPlugin.ID, "sample.no-such-ksp") }
            .message.assert().contains(
                "Project ':' applies the Wow plugin (${WowPlugin.ID}) from a class path without the KSP Gradle plugin",
                "Add id(\"com.google.devtools.ksp\") version \"${WowPlugin.kspVersion}\" to the module's plugins",
            )
    }

    @Test
    fun `the KSP version should be read from its jar`() {
        DomainRole.versionOf(KspExtension::class.java).assert().isEqualTo(WowPlugin.kspVersion)
        DomainRole.versionOf(KeyValueArgs::class.java).assert().isNull()
        // An extension as Gradle creates it, of a class it decorates: the version is the declared class's.
        val decorated = project().objects.newInstance(DecoratedArgs::class.java)
        decorated.javaClass.name.assert().endsWith("_Decorated")
        DomainRole.versionOf(decorated.javaClass).assert().isNull()
    }

    private companion object {
        /** The toolchain of the module, the JDK the build compiles with (Wow's floor); the tests may run on a newer one. */
        const val TOOLCHAIN = 21
    }
}
