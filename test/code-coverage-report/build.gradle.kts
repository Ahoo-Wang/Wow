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
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    id("jacoco-report-aggregation")
}

@Suppress("UNCHECKED_CAST")
val libraryProjects = rootProject.ext.get("libraryProjects") as Iterable<Project>
@Suppress("UNCHECKED_CAST")
val unitTestProjects = rootProject.ext.get("unitTestProjects") as Iterable<Project>
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
            testSuiteName = "unitTest"
        }
    }
}

val coveredTestTasks = listOf(
    unitTestProjects to "unitTest",
    domainTestProjects to "domainTest",
    localContractTestProjects to "contractTest",
    integrationTestProjects to "integrationTest",
).flatMap { (projects, taskName) ->
    projects.map { project ->
        project.tasks.named<Test>(taskName)
    }
}

val mainSourceSets = libraryProjects.map { project ->
    project.extensions.getByType<SourceSetContainer>().named(SourceSet.MAIN_SOURCE_SET_NAME)
}

tasks.named<JacocoReport>("codeCoverageReport") {
    dependsOn(coveredTestTasks)
    sourceDirectories.setFrom(
        mainSourceSets.map { sourceSet ->
            sourceSet.map { it.allSource.srcDirs }
        },
    )
    classDirectories.setFrom(
        mainSourceSets.map { sourceSet ->
            sourceSet.map { it.output.classesDirs }
        },
    )
    executionData.setFrom(
        coveredTestTasks.map { testTask ->
            testTask.map {
                it.extensions.getByType(JacocoTaskExtension::class).destinationFile
            }
        },
    )
}
