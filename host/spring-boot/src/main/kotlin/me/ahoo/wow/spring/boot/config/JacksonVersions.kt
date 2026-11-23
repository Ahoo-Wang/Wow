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

package me.ahoo.wow.spring.boot.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.util.Properties

/**
 * What the host decides of the Jackson it runs: whether Wow's JSON was verified on it, and what to change if not. The
 * minors and the modules are `wow-spi`'s (`META-INF/wow/jackson.properties`, which Wow's build writes from the Jacksons
 * its tests run on); each module's versions are those of the `pom.properties` its jars carry on the class path, every
 * copy of it.
 */
internal object JacksonVersions {
    const val RESOURCE: String = "META-INF/wow/jackson.properties"

    /** Where an application learns how to keep one Jackson on every class path. */
    const val GUIDE: String = "https://wow.ahoo.me/operate/upgrades#jackson"

    private const val DATABIND = "tools.jackson.core:jackson-databind"

    /** The minors Wow's JSON is verified on, and the modules it runs on. */
    data class Verified(val minors: List<String>, val modules: List<String>)

    /** Why Wow refuses to run its JSON on the Jackson found, and the change to make. */
    data class Refusal(val description: String, val action: String)

    /** What [properties] (`minors`, `modules`, comma-separated) name; null when they name none. */
    fun verifiedOf(properties: Properties): Verified? {
        fun list(key: String) =
            properties.getProperty(key).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val minors = list("minors")
        val modules = list("modules")
        return Verified(minors, modules).takeIf { minors.isNotEmpty() && modules.isNotEmpty() }
    }

    /**
     * Why the Jackson [found], each of [verified]'s modules by the versions on the class path, is not one Wow's JSON
     * runs on; null when it is: the modules at one minor, one of [verified]'s. A module found at no version is not
     * checked.
     */
    fun refusal(found: Map<String, List<String>>, verified: Verified): Refusal? {
        val versions = verified.modules.flatMap { module -> found[module].orEmpty().map { module to it } }
        val databind = found[DATABIND].orEmpty().firstOrNull() ?: return null
        val minors = verified.minors.joinToString(" and ")
        return when {
            versions.map { minorOf(it.second) }.distinct().size > 1 -> Refusal(
                "The Jackson modules Wow's JSON runs on are on the class path at different minors: " +
                    versions.joinToString(", ") { (module, version) -> "${module.substringAfter(':')} $version" } + ".",
                "Align them on one version of tools.jackson:jackson-bom, one of Jackson $minors: $GUIDE",
            )

            minorOf(databind) !in verified.minors -> Refusal(
                "The class path holds Jackson $databind, and Wow's JSON is verified on Jackson $minors only.",
                "Keep Jackson at a version Wow is verified on, Spring Boot's, on every class path: $GUIDE",
            )

            else -> null
        }
    }

    private fun minorOf(version: String): String = version.split('.').take(2).joinToString(".")

    /**
     * The versions of [modules] on [classLoader]'s class path, by module (`group:artifact`): the `version` of each
     * `META-INF/maven/<group>/<artifact>/pom.properties` it holds, one for every jar of the module.
     */
    fun versionsOn(classLoader: ClassLoader, modules: List<String>): Map<String, List<String>> =
        modules.associateWith { module ->
            classLoader.getResources("META-INF/maven/${module.replace(':', '/')}/pom.properties").toList()
                .mapNotNull { url -> url.openStream().use { Properties().apply { load(it) } }.getProperty("version") }
                .distinct()
        }
}

/** Refused to run Wow's JSON on the Jackson found ([JacksonVersions.refusal]). */
internal class JacksonVersionException(val refusal: JacksonVersions.Refusal) :
    IllegalStateException(refusal.description)

/**
 * Refuses to start on a Jackson Wow's JSON was not verified on, or on Wow's Jackson modules at two minors: before any
 * bean, once Spring Boot's logging is configured, so the analysis ([JacksonVersionFailureAnalyzer]) reaches the
 * application's log. On a Jackson it runs on, it says nothing but a debug line.
 */
internal class JacksonVersionsInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val classLoader = applicationContext.classLoader ?: javaClass.classLoader
        val verified = classLoader.getResource(JacksonVersions.RESOURCE)
            ?.openStream()?.use { Properties().apply { load(it) } }
            ?.let(JacksonVersions::verifiedOf) ?: return
        val found = JacksonVersions.versionsOn(classLoader, verified.modules)
        JacksonVersions.refusal(found, verified)?.let { throw JacksonVersionException(it) }
        log.debug { "Jackson on the class path: $found; Wow's JSON is verified on ${verified.minors}." }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/** Reports a [JacksonVersionException] at startup: the versions found, and the change to make. */
internal class JacksonVersionFailureAnalyzer : AbstractFailureAnalyzer<JacksonVersionException>() {
    override fun analyze(rootFailure: Throwable, cause: JacksonVersionException): FailureAnalysis =
        FailureAnalysis(cause.refusal.description, cause.refusal.action, cause)
}
