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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * The Jackson gate, part of `check`: fails when a class path the module's Wow code runs on resolves Wow's Jackson
 * modules at a minor Wow's JSON was not verified on, or at two minors. The minors and the modules are `wow-spi`'s, the
 * one on that class path (`META-INF/wow/jackson.properties`), so the gate answers for the Wow the application runs. A
 * class path without `wow-spi`, or with one that names none, passes.
 */
@DisableCachingByDefault(because = "A check of resolution results already computed: cheaper to run than to fetch.")
internal abstract class CheckJackson : DefaultTask() {
    /** Each checked class path's Jackson 3 modules ([JacksonVersions.Found]), by the class path's name. */
    @get:Input
    abstract val resolved: MapProperty<String, List<String>>

    /** The `wow-spi` jars of the checked class paths. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wowSpi: ConfigurableFileCollection

    /** The module, as a failure names it: `project ':shop-server'`. */
    @get:Input
    abstract val owner: Property<String>

    /** Written once the gate passes, so that an unchanged module is up to date. */
    @get:OutputFile
    abstract val passed: RegularFileProperty

    @TaskAction
    fun check() {
        val verified = wowSpi.files.firstNotNullOfOrNull(::verifiedIn)
        if (verified != null) {
            val refusals = resolved.get().mapNotNull { (classpath, found) ->
                JacksonVersions.refusal(owner.get(), classpath, found.map(JacksonVersions.Found::parse), verified)
            }
            if (refusals.isNotEmpty()) throw GradleException(refusals.joinToString("\n\n"))
        }
        passed.get().asFile.writeText("passed\n")
    }

    private fun verifiedIn(jar: File): JacksonVersions.Verified? = ZipFile(jar).use { zip ->
        zip.getEntry(RESOURCE)?.let { entry ->
            JacksonVersions.Verified.of(Properties().apply { zip.getInputStream(entry).reader().use(::load) })
        }
    }

    internal companion object {
        /** The resource of `wow-spi` that names the minors Wow's JSON is verified on and the modules it runs on. */
        const val RESOURCE = "META-INF/wow/jackson.properties"

        private const val WOW_SPI = "wow-spi"

        /**
         * Registers [WowPlugin.JACKSON_CHECK_TASK] in [project], part of `check`, over the test runtime class path and,
         * for a service, the runtime class path too.
         */
        fun register(project: Project, service: Boolean) {
            val names = listOfNotNull(
                JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME,
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME.takeIf { service },
            )
            val check = project.tasks.register(WowPlugin.JACKSON_CHECK_TASK, CheckJackson::class.java) { task ->
                task.group = LifecycleBasePlugin.VERIFICATION_GROUP
                task.description = "Fails when a class path resolves a Jackson Wow's JSON was not verified on."
                task.owner.set("project '${project.path}'")
                names.forEach { name ->
                    val classpath = project.configurations.named(name)
                    task.resolved.put(name, classpath.flatMap(::jacksonOf))
                    task.wowSpi.from(classpath.map { it.filter { file -> file.name.startsWith(WOW_SPI) } })
                }
                task.passed.set(project.layout.buildDirectory.file("wow/jackson-check.txt"))
            }
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { it.dependsOn(check) }
        }

        /** The Jackson 3 modules [configuration] resolves, as [JacksonVersions.Found.format] writes them. */
        private fun jacksonOf(configuration: Configuration): Provider<List<String>> =
            configuration.incoming.resolutionResult.rootComponent.map { root ->
                components(root).mapNotNull { component ->
                    val module = component.moduleVersion ?: return@mapNotNull null
                    if (!module.group.startsWith(JacksonVersions.GROUP)) return@mapNotNull null
                    JacksonVersions.Found("${module.group}:${module.name}", module.version, askedBy(component)).format()
                }.sorted()
            }

        private fun components(root: ResolvedComponentResult): Set<ResolvedComponentResult> {
            val seen = linkedSetOf<ResolvedComponentResult>()
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (seen.add(component)) {
                    component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending += it.selected }
                }
            }
            return seen
        }

        /** The first module outside Jackson that asked for [component] at the version selected, if one did. */
        private fun askedBy(component: ResolvedComponentResult): String? {
            val version = component.moduleVersion?.version
            return component.dependents.firstNotNullOfOrNull { dependent ->
                val requested = dependent.requested as? ModuleComponentSelector
                val from = dependent.from.moduleVersion
                from?.takeIf { requested?.version == version && !it.group.startsWith(JacksonVersions.GROUP) }
                    ?.let { "${it.group}:${it.name}:${it.version}" }
            }
        }
    }
}

/** What [CheckJackson] decides: whether a class path's Jackson is one Wow's JSON runs on, and what to change if not. */
internal object JacksonVersions {
    /** The group of Jackson 3's modules. */
    const val GROUP = "tools.jackson"

    /** Where an application learns how to keep one Jackson on every class path. */
    const val GUIDE = "https://wow.ahoo.me/operate/upgrades#jackson"

    private const val DATABIND = "tools.jackson.core:jackson-databind"

    /** A Jackson 3 module a class path resolves, at [version], and the module that asked for that version, if any. */
    data class Found(val module: String, val version: String, val askedBy: String?) {
        /** The minor of [version], `3.2` of `3.2.3`. */
        val minor: String = version.split('.').take(2).joinToString(".")

        fun format(): String = listOfNotNull(module, version, askedBy).joinToString(SEPARATOR)

        companion object {
            private const val SEPARATOR = " "

            fun parse(text: String): Found = text.split(SEPARATOR).let { Found(it[0], it[1], it.getOrNull(2)) }
        }
    }

    /** The minors Wow's JSON is verified on, and the modules it runs on, as `wow-spi` names them. */
    data class Verified(val minors: List<String>, val modules: List<String>) {
        companion object {
            /** What [properties] (`minors`, `modules`, comma-separated) name; null when they name none. */
            fun of(properties: Properties): Verified? {
                fun list(key: String) = properties.getProperty(key).orEmpty().split(',').map(String::trim)
                    .filter(String::isNotEmpty)
                val minors = list("minors")
                val modules = list("modules")
                return Verified(minors, modules).takeIf { minors.isNotEmpty() && modules.isNotEmpty() }
            }
        }
    }

    /**
     * Why [owner]'s [classpath], which resolves [found], runs Wow's JSON on a Jackson it was not verified on, and the
     * change to make; null when it does not: Wow's modules there are of one minor, one of [verified]'s.
     */
    fun refusal(owner: String, classpath: String, found: List<Found>, verified: Verified): String? {
        val wows = found.filter { it.module in verified.modules }
        val databind = wows.firstOrNull { it.module == DATABIND } ?: return null
        val minors = verified.minors.joinToString(" and ")
        return when {
            wows.map(Found::minor).distinct().size > 1 ->
                "The $classpath of $owner resolves the Jackson modules Wow's JSON runs on at different minors: " +
                    wows.joinToString(", ") { "${it.module.substringAfter(':')} ${it.version}" } + ". " +
                    "Align them on one version of tools.jackson:jackson-bom, one of Jackson $minors: $GUIDE"

            databind.minor !in verified.minors ->
                "The $classpath of $owner resolves Jackson ${databind.version}" +
                    (databind.askedBy?.let { ", asked for by $it" } ?: "") +
                    ", and Wow's JSON is verified on Jackson $minors only. " +
                    "Keep Jackson at a version Wow is verified on, Spring Boot's, on every class path: $GUIDE"

            else -> null
        }
    }
}
