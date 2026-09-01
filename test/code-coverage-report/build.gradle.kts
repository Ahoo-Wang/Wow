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

import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    id("jacoco-report-aggregation")
}

@Suppress("UNCHECKED_CAST")
val libraryProjects = rootProject.ext.get("libraryProjects") as Iterable<Project>
@Suppress("UNCHECKED_CAST")
val localTestProjects = rootProject.ext.get("localTestProjects") as Iterable<Project>
@Suppress("UNCHECKED_CAST")
val localContractTestProjects = rootProject.ext.get("localContractTestProjects") as Iterable<Project>
@Suppress("UNCHECKED_CAST")
val integrationTestProjects = rootProject.ext.get("integrationTestProjects") as Iterable<Project>

dependencies {
    libraryProjects.forEach {
        jacocoAggregation(it)
    }
}

reporting {
    reports {
        val codeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName = "test"
        }
    }
}

fun testTasks(projects: Iterable<Project>, taskName: String): List<TaskProvider<Test>> {
    return projects.map { project ->
        project.tasks.named<Test>(taskName)
    }
}

fun mainSourceSets(projects: Iterable<Project>) = projects.map { project ->
    project.extensions.getByType<SourceSetContainer>().named(SourceSet.MAIN_SOURCE_SET_NAME)
}

fun JacocoReport.useCoverageData(
    projects: Iterable<Project>,
    testTasks: Iterable<TaskProvider<Test>>,
) {
    dependsOn(testTasks)
    sourceDirectories.setFrom(
        mainSourceSets(projects).map { sourceSet ->
            sourceSet.map { it.allSource.srcDirs }
        },
    )
    classDirectories.setFrom(
        mainSourceSets(projects).map { sourceSet ->
            sourceSet.map { it.output.classesDirs }
        },
    )
    executionData.setFrom(
        testTasks.map { testTask ->
            testTask.map {
                it.extensions.getByType(JacocoTaskExtension::class).destinationFile
            }
        },
    )
}

fun registerLayerCoverageReport(
    taskName: String,
    projects: Iterable<Project>,
    testTaskName: String,
) {
    val layerTestTasks = testTasks(projects, testTaskName)
    tasks.register<JacocoReport>(taskName) {
        description = "Generates the ${taskName.removeSuffix("CoverageReport")} coverage report."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        reports {
            xml.required.set(true)
            xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/$taskName/$taskName.xml"))
            csv.required.set(false)
            html.required.set(false)
        }
        useCoverageData(projects, layerTestTasks)
    }
}

fun registerLocalTestShard(taskName: String, projects: Iterable<Project>) {
    tasks.register(taskName) {
        description = "Runs $taskName tests."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(projects.map { "${it.path}:test" })
    }
}

val localTestTasks = testTasks(localTestProjects, "test")
val contractTestTasks = testTasks(localContractTestProjects, "contractTest")
val integrationTestTasks = testTasks(integrationTestProjects, "integrationTest")
val coveredTestTasks = localTestTasks + contractTestTasks + integrationTestTasks
val localCoverageProjects = localTestProjects.toSet()
val localTestTaskProjects = localCoverageProjects + project(":wow-compensation-server")
val localTestShard1Paths = setOf(
    ":wow-core",
    ":wow-openapi",
    ":wow-bi",
    ":wow-elasticsearch",
    ":wow-query",
    ":wow-kafka",
    ":wow-opentelemetry",
    ":wow-cocache",
    ":wow-compensation-core",
    ":example-transfer-domain",
    ":wow-tck",
    ":wow-spring",
    ":wow-compensation-api",
    ":example-api",
    ":example-transfer-api",
)
val localTestShard1 = localTestTaskProjects.filter { it.path in localTestShard1Paths }.toSet()
val resolvedLocalTestShard1Paths = localTestShard1.map { it.path }.toSet()
val missingLocalTestShard1Paths = localTestShard1Paths - resolvedLocalTestShard1Paths
require(missingLocalTestShard1Paths.isEmpty()) {
    "Local Test shard 1 contains unknown or non-local projects: $missingLocalTestShard1Paths"
}
val localTestShard2 = localTestTaskProjects - localTestShard1
require(localTestShard1.isNotEmpty()) { "Local Test shard 1 must not be empty." }
require(localTestShard2.isNotEmpty()) { "Local Test shard 2 must not be empty." }
require(localTestShard1.intersect(localTestShard2).isEmpty()) { "Local Test shards must not overlap." }
require(localTestShard1 + localTestShard2 == localTestTaskProjects) {
    "Local Test shards must cover every local test project."
}

fun Project.artifactClassDirectories() = listOf(
    layout.buildDirectory.dir("classes/kotlin/main").get().asFile,
    layout.buildDirectory.dir("classes/java/main").get().asFile,
)

fun Project.artifactExecutionData() = layout.buildDirectory.file("jacoco/test.exec").get().asFile

fun Project.hasSource(sourceSetName: String): Boolean {
    return extensions.getByType<SourceSetContainer>()
        .named(sourceSetName)
        .get()
        .allSource.files
        .any(File::isFile)
}

val artifactClassDirectories = localCoverageProjects.flatMap { it.artifactClassDirectories() }
val artifactExecutionData = localCoverageProjects.map { it.artifactExecutionData() }
val verifyLocalCoverageArtifacts = tasks.register("verifyLocalCoverageArtifacts") {
    description = "Verifies downloaded Local Test coverage artifacts."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    doLast {
        val missingClassProjects = localCoverageProjects.filter { project ->
            project.hasSource(SourceSet.MAIN_SOURCE_SET_NAME) &&
                project.artifactClassDirectories().none { classDirectory ->
                    classDirectory.walkTopDown().any { it.isFile && it.extension == "class" }
                }
        }
        require(missingClassProjects.isEmpty()) {
            "Missing Local Test main classes: ${missingClassProjects.map { it.path }}"
        }
        val missingExecutionDataProjects = localCoverageProjects.filter { project ->
            project.hasSource(SourceSet.TEST_SOURCE_SET_NAME) && !project.artifactExecutionData().isFile
        }
        require(missingExecutionDataProjects.isEmpty()) {
            "Missing Local Test execution data: ${missingExecutionDataProjects.map { it.path }}"
        }
    }
}

tasks.named<JacocoReport>("codeCoverageReport") {
    useCoverageData(libraryProjects, coveredTestTasks)
}

registerLayerCoverageReport("localCoverageReport", localTestProjects, "test")
registerLocalTestShard("localTestShard1", localTestShard1)
registerLocalTestShard("localTestShard2", localTestShard2)
tasks.register<JacocoReport>("localCoverageReportFromArtifacts") {
    description = "Generates Local Test coverage from downloaded artifacts."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(verifyLocalCoverageArtifacts)
    reports {
        xml.required.set(true)
        xml.outputLocation.set(
            layout.buildDirectory.file(
                "reports/jacoco/localCoverageReportFromArtifacts/localCoverageReportFromArtifacts.xml",
            ),
        )
        csv.required.set(false)
        html.required.set(false)
    }
    sourceDirectories.setFrom(
        mainSourceSets(localCoverageProjects).map { sourceSet ->
            sourceSet.map { it.allSource.srcDirs }
        },
    )
    classDirectories.setFrom(artifactClassDirectories.filter(File::isDirectory))
    executionData.setFrom(artifactExecutionData.filter(File::isFile))
}
registerLayerCoverageReport("contractCoverageReport", localContractTestProjects, "contractTest")
registerLayerCoverageReport("integrationCoverageReport", integrationTestProjects, "integrationTest")
