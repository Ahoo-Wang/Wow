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

// D85: the V10 wow-api (:api) declares 14 annotations under the names 9.x wow-api keeps, and JaCoCo refuses two
// different classes of one name. Annotations hold no code to cover, so the reports leave out :api's.
val classesSharedWithNineX = mapOf(":api" to listOf("me/ahoo/wow/api/annotation/**"))

// D125: the V10 query protocol (:query-api) keeps the package and class names of 9.x wow-api's `query`, code included.
// A report that covers both (the full build's 9.x reports) leaves out :query-api's; the V10 reports, which have no
// 9.x wow-api, keep them. D126: the V10 query engine (:query) likewise keeps 9.x wow-query's package and many of its
// class names (`me.ahoo.wow.query.QueryAdmission`, ...), so a report that covers both leaves out :query's. D126 also
// gives the V10 MongoDB backend (:mongo) the 9.x package of wow-mongo's query backend, `me.ahoo.wow.mongo.query`,
// where both declare `MongoCursorCodec`, so a report that covers both leaves out :mongo's query classes. The V10
// ClickHouse dataset backend (:clickhouse, 6.4a) is the port of 9.x wow-clickhouse's, in its package
// `me.ahoo.wow.clickhouse.query` and under many of its class names, so a report that covers both leaves out its classes.
val classesSharedWithNineXWhenBoth = mapOf(
    ":query-api" to (":wow-api" to listOf("me/ahoo/wow/api/query/**")),
    ":query" to (":wow-query" to listOf("me/ahoo/wow/query/**")),
    ":mongo" to (":wow-mongo" to listOf("me/ahoo/wow/mongo/query/**")),
    ":clickhouse" to (":wow-clickhouse" to listOf("me/ahoo/wow/clickhouse/query/**")),
)

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
    val paths = projects.map { it.path }.toSet()
    classDirectories.setFrom(
        projects.map { project ->
            val classesDirs = mainSourceSets(listOf(project)).single().map { it.output.classesDirs }
            val shared = classesSharedWithNineX[project.path].orEmpty() +
                classesSharedWithNineXWhenBoth[project.path]
                    ?.takeIf { (nineX, _) -> nineX in paths }
                    ?.second
                    .orEmpty()
            if (shared.isEmpty()) classesDirs else files(classesDirs).asFileTree.matching { exclude(shared) }
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

val localTestTasks = testTasks(localTestProjects, "test")
val contractTestTasks = testTasks(localContractTestProjects, "contractTest")
val integrationTestTasks = testTasks(integrationTestProjects, "integrationTest")
val coveredTestTasks = localTestTasks + contractTestTasks + integrationTestTasks

tasks.named<JacocoReport>("codeCoverageReport") {
    useCoverageData(libraryProjects, coveredTestTasks)
}

registerLayerCoverageReport("localCoverageReport", localTestProjects, "test")
registerLayerCoverageReport("contractCoverageReport", localContractTestProjects, "contractTest")
registerLayerCoverageReport("integrationCoverageReport", integrationTestProjects, "integrationTest")

// v10.yml uploads these under the `v10` Codecov flag, in either build scope (`-Pwow.scope=v10` leaves the 9.x modules
// out): the unit tests of the V10 modules, and their container-backed tests.
@Suppress("UNCHECKED_CAST")
val v10Projects = rootProject.ext.get("v10Projects") as Iterable<Project>
@Suppress("UNCHECKED_CAST")
val v10IntegrationTestProjects = rootProject.ext.get("v10IntegrationTestProjects") as Iterable<Project>
registerLayerCoverageReport("v10CoverageReport", v10Projects, "test")
registerLayerCoverageReport("v10IntegrationCoverageReport", v10IntegrationTestProjects, "integrationTest")
