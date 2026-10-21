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
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    base
    id("jacoco-report-aggregation")
}

// The end-to-end architecture benchmark's modules (decision D224) are applications measured, not tested for coverage:
// every report leaves them out.
@Suppress("UNCHECKED_CAST")
val e2eBenchmarkProjects = (rootProject.ext.get("e2eBenchmarkProjects") as Iterable<Project>).toSet()
@Suppress("UNCHECKED_CAST")
val libraryProjects = (rootProject.ext.get("libraryProjects") as Iterable<Project>) - e2eBenchmarkProjects
@Suppress("UNCHECKED_CAST")
val localTestProjects = (rootProject.ext.get("localTestProjects") as Iterable<Project>) - e2eBenchmarkProjects
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

/** A module of a report: its main source roots, and the class files the report covers of it. */
class CoveredModule(val sourceRoots: FileCollection, val classes: FileTree)

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
    val modules = projects.map { project ->
        val main = mainSourceSets(listOf(project)).single()
        CoveredModule(
            sourceRoots = files(main.map { it.allSource.srcDirs }),
            classes = files(main.map { it.output.classesDirs }).asFileTree,
        )
    }
    classDirectories.setFrom(modules.map { it.classes })
    executionData.setFrom(
        testTasks.map { testTask ->
            testTask.map {
                it.extensions.getByType(JacocoTaskExtension::class).destinationFile
            }
        },
    )
    val repository = rootProject.layout.projectDirectory.asFile
    doLast {
        reports.xml.outputLocation.get().asFile.takeIf { it.isFile }?.let { xml ->
            nameSourcesByRepositoryPath(xml, modules, repository)
        }
    }
}

/**
 * Names every source file of the JaCoCo report [xml] by its path in the repository, so Codecov maps each to the one
 * file it is. JaCoCo names a source file by its package, `<package>/<file>`, and Codecov resolves that path against
 * the repository's files; the V10 modules keep the packages and file names of 9.x ones (`me.ahoo.wow.api.query`,
 * `me.ahoo.wow.query`, `me.ahoo.wow.clickhouse.query`, ...), so such a path names a file of each, and Codecov counted
 * the lines of one on the other. Here each source file moves, with its classes, into a package named after the source
 * root of the module whose class files hold its classes (`query/query/src/main/kotlin/me/ahoo/wow/query`); a class is
 * named the same way, which keeps Codecov's method complexity, keyed by class name, on its file. A source file whose
 * module or root is not found keeps its package path.
 */
fun nameSourcesByRepositoryPath(xml: File, modules: List<CoveredModule>, repository: File) {
    val classesOf = modules.map { module ->
        module.classes.visitRelative()
    }
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val document = factory.newDocumentBuilder().parse(xml)
    val report = document.documentElement
    report.childElements("package").forEach { pkg ->
        val packagePath = pkg.getAttribute("name")
        val classes = pkg.childElements("class")
        val byRoot = pkg.childElements("sourcefile").groupBy { source ->
            val name = source.getAttribute("name")
            val owners = classes.filter { it.getAttribute("sourcefilename") == name }
                .flatMap { type -> classesOf.indices.filter { "${type.getAttribute("name")}.class" in classesOf[it] } }
                .distinct()
            val candidates = (owners.singleOrNull()?.let { listOf(modules[it]) } ?: modules)
                .flatMap { it.sourceRoots.files }
                .filter { File(it, "$packagePath/$name").isFile }
                .distinct()
            candidates.singleOrNull()?.relativeTo(repository)?.invariantSeparatorsPath
        }
        byRoot.forEach { (root, sources) ->
            if (root == null) return@forEach
            val moved = document.createElement("package").apply { setAttribute("name", "$root/$packagePath") }
            sources.forEach { source ->
                val name = source.getAttribute("name")
                classes.filter { it.getAttribute("sourcefilename") == name }.forEach { type ->
                    type.setAttribute("name", "$root/${type.getAttribute("name")}")
                    moved.appendChild(type)
                }
                moved.appendChild(source)
            }
            report.insertBefore(moved, pkg)
        }
        if (pkg.childElements("sourcefile").isEmpty() && pkg.childElements("class").isEmpty()) {
            report.removeChild(pkg)
        }
    }
    TransformerFactory.newInstance().newTransformer().apply {
        document.doctype?.let { doctype ->
            setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, doctype.publicId)
            setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, doctype.systemId)
        }
    }.transform(DOMSource(document), StreamResult(xml))
}

fun FileTree.visitRelative(): Set<String> {
    val paths = HashSet<String>()
    visit { if (!isDirectory) paths += relativePath.pathString }
    return paths
}

fun Element.childElements(tag: String): List<Element> =
    (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == tag }

fun registerLayerCoverageReport(
    taskName: String,
    projects: Iterable<Project>,
    testTaskName: String,
    testProjects: Iterable<Project> = projects,
) {
    val layerTestTasks = testTasks(testProjects, testTaskName)
    tasks.register<JacocoReport>(taskName) {
        description = "Generates the ${taskName.removeSuffix("CoverageReport").ifEmpty { "unit test" }} coverage report."
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

// Every module's unit and container-backed tests together, for a local look.
val coveredTestTasks = testTasks(localTestProjects, "test") + testTasks(integrationTestProjects, "integrationTest")

tasks.named<JacocoReport>("codeCoverageReport") {
    useCoverageData(libraryProjects, coveredTestTasks)
}

// ci.yml uploads these under the `unit` and `integration` Codecov flags: the modules' unit tests, and their
// container-backed tests.
@Suppress("UNCHECKED_CAST")
val moduleProjects = (rootProject.ext.get("moduleProjects") as Iterable<Project>) - e2eBenchmarkProjects
registerLayerCoverageReport("coverageReport", moduleProjects, "test")
// A shard of ci.yml's integration matrix (`-Pwow.integration.projects`) reports the test data of its own projects.
@Suppress("UNCHECKED_CAST")
val integrationShardProjects = rootProject.ext.get("integrationShardProjects") as Iterable<Project>
registerLayerCoverageReport(
    "integrationCoverageReport",
    integrationTestProjects,
    "integrationTest",
    integrationShardProjects,
)
