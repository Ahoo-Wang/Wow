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

import me.ahoo.wow.gradle.DomainRole.FRAGMENT_DIRECTORY
import me.ahoo.wow.gradle.WowArtifacts.ownersOf
import me.ahoo.wow.gradle.WowArtifacts.toolsConfiguration
import me.ahoo.wow.gradle.WowPlugin.Companion.BASELINE_FILE
import me.ahoo.wow.gradle.WowPlugin.Companion.CHECK_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.GENERATE_FRAGMENT_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.HELM_VALUES_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.INTEGRATION_TEST
import me.ahoo.wow.gradle.WowPlugin.Companion.KOTLIN_JVM
import me.ahoo.wow.gradle.WowPlugin.Companion.MERGE_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.OPENAPI_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.UPDATE_BASELINE_TASK
import me.ahoo.wow.gradle.WowPlugin.Companion.defaultServiceName
import me.ahoo.wow.gradle.WowPlugin.Companion.version
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.base.TestingExtension

/**
 * The service role of a project ([service]: `me.ahoo.wow.service`, on top of the domain role of its own sources):
 * the merged Manifest, its OpenAPI document and Helm values, the compatibility gate against its baseline, and the
 * container-backed integration test suite.
 */
internal object ServiceRole {
    /**
     * The service role of [project]: the service's Manifest merged from the fragments of its runtime class path, its
     * OpenAPI document, the values of the `wow-service` Helm chart it knows, the gate against its committed baseline,
     * and the integration test suite.
     */
    fun service(project: Project, extension: WowServiceExtension) {
        extension.manifestBaseline.convention(project.layout.projectDirectory.file(BASELINE_FILE))
        project.pluginManager.withPlugin(KOTLIN_JVM) { configureService(project, extension) }
    }

    private fun configureService(project: Project, extension: WowServiceExtension) {
        configureIntegrationTest(project)
        val tools = toolsConfiguration(project)
        val main = project.extensions.getByType(
            SourceSetContainer::class.java
        ).getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val runtimeClasspath = project.configurations.named(main.runtimeClasspathConfigurationName)
        val generate = project.tasks.named(GENERATE_FRAGMENT_TASK)
        val fragmentDirectory = project.layout.buildDirectory.dir(FRAGMENT_DIRECTORY)
        val merge = project.tasks.register(MERGE_TASK, MergeManifest::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Merges the Manifest fragments on the runtime class path into the service's manifest.json."
            // The module's own completed fragment, none when it has no model or processor, and its dependencies'.
            task.classpath.from(project.files(fragmentDirectory).builtBy(generate), runtimeClasspath)
            val owner = "project '${project.path}'"
            task.owners.putAll(fragmentDirectory.map { mapOf(it.asFile.path to owner) })
            task.owners.putAll(runtimeClasspath.flatMap(::ownersOf))
            task.tools.from(tools)
            // The module's own contexts name their service as the contracts on the class path do.
            task.serviceName.set(extension.service)
            task.defaultServiceName.set(defaultServiceName(project.path, project.rootProject.name))
            task.serviceVersion.set(project.provider { project.version.toString() })
            task.outputFile.set(project.layout.buildDirectory.file("wow/manifest.json"))
        }
        project.tasks.register(OPENAPI_TASK, GenerateOpenApi::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Writes the OpenAPI document of the service's routes from its manifest.json."
            task.manifestFile.set(merge.flatMap { it.outputFile })
            task.tools.from(tools)
            task.outputFile.set(project.layout.buildDirectory.file("wow/openapi.json"))
        }
        project.tasks.register(HELM_VALUES_TASK, GenerateHelmValues::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Writes the values of the wow-service Helm chart the build knows: the service's name, " +
                "its peers and its Manifest's route."
            task.manifestFile.set(merge.flatMap { it.outputFile })
            task.classpath.from(runtimeClasspath)
            task.tools.from(tools)
            task.outputFile.set(project.layout.buildDirectory.file("wow/helm/values.yaml"))
        }
        compatibility(project, extension, merge, tools)
    }

    /**
     * The Manifest compatibility gate: [CHECK_TASK] compares the merged Manifest with the committed
     * [WowServiceExtension.manifestBaseline] and is part of `check`; [UPDATE_BASELINE_TASK] rewrites the baseline.
     */
    private fun compatibility(
        project: Project,
        extension: WowServiceExtension,
        merge: TaskProvider<MergeManifest>,
        tools: Configuration,
    ) {
        val update = project.tasks.register(UPDATE_BASELINE_TASK, UpdateManifestBaseline::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Records the merged Manifest as the baseline $CHECK_TASK compares with."
            task.manifest.set(merge.flatMap { it.outputFile })
            task.tools.from(tools)
            task.baseline.set(extension.manifestBaseline)
            task.allowBreaking.set(
                project.providers.gradleProperty(
                    WowPlugin.ALLOW_BREAKING_PROPERTY
                ).map(String::toBoolean).orElse(false),
            )
            task.owner.set("project '${project.path}'")
            task.report.set(project.layout.buildDirectory.file("wow/manifest-baseline.txt"))
        }
        val check = project.tasks.register(CHECK_TASK, CheckManifestCompatibility::class.java) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description = "Fails when the merged Manifest breaks its baseline, or the baseline does not record it."
            task.baseline.set(extension.manifestBaseline)
            task.manifest.set(merge.flatMap { it.outputFile })
            task.tools.from(tools)
            // Recording the baseline and checking against it in one build checks the new baseline.
            task.mustRunAfter(update)
            task.report.set(project.layout.buildDirectory.file("wow/manifest-compatibility.txt"))
            task.owner.set("project '${project.path}'")
        }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { it.dependsOn(check) }
    }

    /**
     * The test suite [INTEGRATION_TEST] of a service module, for the tests that run on containers: JUnit Jupiter,
     * against the module's classes and everything `testImplementation` and `testRuntimeOnly` give, nothing more; the
     * build declares what else its tests need (Testcontainers) in the suite. It is not part of `check`:
     * `./gradlew integrationTest` runs it.
     *
     * It is registered once the Kotlin JVM plugin is applied, unless a suite or a source set of that name exists by
     * then: one a convention plugin applied first or the root build set up stays the build's, unchanged. The module's
     * own build script runs after its plugins, so it configures this suite
     * (`testing.suites.named<JvmTestSuite>("integrationTest")`) rather than registering one.
     */
    private fun configureIntegrationTest(project: Project) {
        val suites = project.extensions.getByType(TestingExtension::class.java).suites
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        // A build that sets its integration tests up itself, as a suite or as a source set of its own, keeps them.
        if (suites.findByName(INTEGRATION_TEST) != null || sourceSets.findByName(INTEGRATION_TEST) != null) {
            return
        }
        suites.register(INTEGRATION_TEST, JvmTestSuite::class.java) { suite ->
            suite.useJUnitJupiter()
            suite.dependencies { dependencies -> dependencies.implementation.add(dependencies.project()) }
            suite.targets.configureEach { target ->
                target.testTask.configure { task ->
                    task.description = "Runs the container-backed integration tests."
                    task.shouldRunAfter(project.tasks.named(JavaPlugin.TEST_TASK_NAME))
                }
            }
        }
        project.configurations.named("${INTEGRATION_TEST}Implementation") {
            it.extendsFrom(project.configurations.getByName(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME))
        }
        project.configurations.named("${INTEGRATION_TEST}RuntimeOnly") {
            it.extendsFrom(project.configurations.getByName(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME))
        }
    }

    private const val GROUP_NAME = "wow"
}
