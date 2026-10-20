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
// The V10 Elasticsearch backend (:elasticsearch, 6.7) keeps 9.x wow-elasticsearch's package `me.ahoo.wow.elasticsearch`
// and ports its query compilers under their class names, likewise.
// D216: the V10 test DSL (:test-dsl) is in 9.x wow-test's package `me.ahoo.wow.test`, where both declare
// `AggregateSpec`, and the V10 TCK (:tck) shares 9.x wow-tck's root `me.ahoo.wow.tck`, so a report that covers both
// leaves out the V10 module's classes. The V10 view-store (D146, D216) keeps 9.x view-store's packages
// `me.ahoo.wow.viewstore.*` and many of its class names, so a report that covers both leaves out the V10 module's.
// The V10 models (:models, D187) keep 9.x wow-models' package `me.ahoo.wow.models` and its class names, likewise.
val classesSharedWithNineXWhenBoth = mapOf(
    ":query-api" to (":wow-api" to listOf("me/ahoo/wow/api/query/**")),
    ":query" to (":wow-query" to listOf("me/ahoo/wow/query/**")),
    ":mongo" to (":wow-mongo" to listOf("me/ahoo/wow/mongo/query/**")),
    ":clickhouse" to (":wow-clickhouse" to listOf("me/ahoo/wow/clickhouse/query/**")),
    ":elasticsearch" to (":wow-elasticsearch" to listOf("me/ahoo/wow/elasticsearch/**")),
    ":test-dsl" to (":wow-test" to listOf("me/ahoo/wow/test/**")),
    ":tck" to (":wow-tck" to listOf("me/ahoo/wow/tck/**")),
    ":view-store-api" to (":wow-view-store-api" to listOf("me/ahoo/wow/viewstore/**")),
    ":view-store-domain" to (":wow-view-store-domain" to listOf("me/ahoo/wow/viewstore/**")),
    ":view-store-server" to (":wow-view-store-server" to listOf("me/ahoo/wow/viewstore/**")),
    ":models" to (":wow-models" to listOf("me/ahoo/wow/models/**")),
)

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
    val paths = projects.map { it.path }.toSet()
    val modules = projects.map { project ->
        val main = mainSourceSets(listOf(project)).single()
        val shared = classesSharedWithNineX[project.path].orEmpty() +
            classesSharedWithNineXWhenBoth[project.path]
                ?.takeIf { (nineX, _) -> nineX in paths }
                ?.second
                .orEmpty()
        CoveredModule(
            sourceRoots = files(main.map { it.allSource.srcDirs }),
            classes = files(main.map { it.output.classesDirs }).asFileTree.matching { exclude(shared) },
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
val v10Projects = (rootProject.ext.get("v10Projects") as Iterable<Project>) - e2eBenchmarkProjects
@Suppress("UNCHECKED_CAST")
val v10IntegrationTestProjects = rootProject.ext.get("v10IntegrationTestProjects") as Iterable<Project>
registerLayerCoverageReport("v10CoverageReport", v10Projects, "test")
registerLayerCoverageReport("v10IntegrationCoverageReport", v10IntegrationTestProjects, "integrationTest")
