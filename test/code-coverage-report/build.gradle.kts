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
val standardTestProjects = rootProject.ext.get("standardTestProjects") as Iterable<Project>
@Suppress("UNCHECKED_CAST")
val domainTestProjects = rootProject.ext.get("domainTestProjects") as Iterable<Project>
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

val standardTestTasks = testTasks(standardTestProjects, "test")
val contractTestTasks = testTasks(localContractTestProjects, "contractTest")
val integrationTestTasks = testTasks(integrationTestProjects, "integrationTest")
val coveredTestTasks = standardTestTasks + contractTestTasks + integrationTestTasks

tasks.named<JacocoReport>("codeCoverageReport") {
    useCoverageData(libraryProjects, coveredTestTasks)
}

registerLayerCoverageReport("unitCoverageReport", standardTestProjects, "test")
registerLayerCoverageReport("domainCoverageReport", domainTestProjects, "test")
registerLayerCoverageReport("contractCoverageReport", localContractTestProjects, "contractTest")
registerLayerCoverageReport("integrationCoverageReport", integrationTestProjects, "integrationTest")
