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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.config.JacksonVersions.Verified
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.UrlResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import org.springframework.core.io.support.SpringFactoriesLoader
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.Properties

/**
 * The host refuses to start on a Jackson Wow's JSON was not verified on, or on Wow's Jackson modules at two minors, as
 * the `wow-spi` on its class path names them; on Wow's own Jackson it starts.
 */
class JacksonVersionsTest {
    @TempDir
    lateinit var directory: File

    private val verified = Verified(listOf("3.1", "3.2"), listOf(CORE, DATABIND, KOTLIN))

    @Test
    fun `Wow's modules at one verified minor should run, a patch apart too, and a class path without databind`() {
        JacksonVersions.refusal(mapOf(CORE to listOf("3.1.8"), DATABIND to listOf("3.1.8")), verified).assert().isNull()
        JacksonVersions.refusal(mapOf(CORE to listOf("3.2.1"), DATABIND to listOf("3.2.3")), verified).assert().isNull()
        JacksonVersions.refusal(mapOf(CORE to listOf("3.0.3")), verified).assert().isNull()
        // A module only the application uses is the application's.
        JacksonVersions.refusal(mapOf(DATABIND to listOf("3.2.3"), YAML to listOf("3.3.0")), verified).assert().isNull()
    }

    @Test
    fun `a minor Wow was not verified on should be refused with the change to make`() {
        JacksonVersions.refusal(mapOf(DATABIND to listOf("3.3.0")), verified).assert().isEqualTo(
            JacksonVersions.Refusal(
                "The class path holds Jackson 3.3.0, and Wow's JSON is verified on Jackson 3.1 and 3.2 only.",
                "Keep Jackson at a version Wow is verified on, Spring Boot's, on every class path: " +
                    JacksonVersions.GUIDE,
            ),
        )
    }

    @Test
    fun `two minors should be refused, two jars of one module among them`() {
        JacksonVersions.refusal(
            mapOf(CORE to listOf("3.2.3"), DATABIND to listOf("3.2.3"), KOTLIN to listOf("3.1.8")),
            verified,
        )!!.assert().isEqualTo(
            JacksonVersions.Refusal(
                "The Jackson modules Wow's JSON runs on are on the class path at different minors: " +
                    "jackson-core 3.2.3, jackson-databind 3.2.3, jackson-module-kotlin 3.1.8.",
                "Align them on one version of tools.jackson:jackson-bom, one of Jackson 3.1 and 3.2: " +
                    JacksonVersions.GUIDE,
            ),
        )
        JacksonVersions.refusal(mapOf(DATABIND to listOf("3.1.8", "3.2.3")), verified)!!.description
            .assert().contains("jackson-databind 3.1.8, jackson-databind 3.2.3")
    }

    @Test
    fun `the verified minors and modules should be wow-spi's, and none when it names none`() {
        val properties = Properties().apply {
            setProperty("minors", "3.1, 3.2")
            setProperty("modules", "$CORE,$DATABIND")
        }
        val read = JacksonVersions.verifiedOf(properties)
        read.assert().isEqualTo(Verified(listOf("3.1", "3.2"), listOf(CORE, DATABIND)))
        JacksonVersions.verifiedOf(Properties()).assert().isNull()
        JacksonVersions.verifiedOf(Properties().apply { setProperty("minors", "3.1") }).assert().isNull()
    }

    /** A class path's resources: `wow-spi`'s list naming [minors], and a `pom.properties` for each of [versions]. */
    private fun classLoader(minors: String?, versions: Map<String, String>): ClassLoader {
        val resources = buildMap {
            if (minors != null) {
                put(JacksonVersions.RESOURCE, file("wow-spi", "minors=$minors\nmodules=$CORE,$DATABIND,$KOTLIN\n"))
            }
            versions.forEach { (module, version) ->
                put("META-INF/maven/${module.replace(':', '/')}/pom.properties", file(module, "version=$version\n"))
            }
        }
        // Class loading as the test's; the resources, those alone.
        return object : ClassLoader(javaClass.classLoader) {
            override fun getResource(name: String): URL? =
                if (name in OWN) resources[name] else super.getResource(name)

            override fun getResources(name: String): Enumeration<URL> =
                if (name in OWN || name.startsWith("META-INF/maven/tools.jackson")) {
                    Collections.enumeration(listOfNotNull(resources[name]))
                } else {
                    super.getResources(name)
                }
        }
    }

    private fun file(name: String, content: String): URL =
        File(directory, name.replace(':', '_')).apply { writeText(content) }.toURI().toURL()

    private fun initialize(classLoader: ClassLoader) =
        JacksonVersionsInitializer().initialize(GenericApplicationContext().apply { setClassLoader(classLoader) })

    @Test
    fun `the class path's own Jackson and wow-spi, Wow's build's, should start`() {
        initialize(javaClass.classLoader)
        val found = JacksonVersions.versionsOn(javaClass.classLoader, listOf(DATABIND))
        found.getValue(DATABIND).assert().hasSize(1)
    }

    @Test
    fun `a class path at a minor wow-spi does not name should not start, and one without its list should`() {
        val unverified = mapOf(CORE to "3.0.3", DATABIND to "3.0.3", KOTLIN to "3.0.3")
        assertThrows<JacksonVersionException> { initialize(classLoader("3.1,3.2", unverified)) }.refusal.description
            .assert().contains("Jackson 3.0.3")
        initialize(classLoader(null, unverified))
    }

    @Configuration(proxyBeanMethods = false)
    class Empty

    @Test
    fun `Spring Boot should refuse to start on it, and analyze the refusal`() {
        val mixed = mapOf(CORE to "3.2.3", DATABIND to "3.2.3", KOTLIN to "3.1.8")
        val application = SpringApplication(Empty::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setResourceLoader(DefaultResourceLoader(classLoader("3.1,3.2", mixed)))
        }
        val refused = assertThrows<JacksonVersionException> { application.run() }
        val analysis = JacksonVersionFailureAnalyzer().analyze(IllegalStateException(refused))!!
        analysis.description.assert().contains("jackson-module-kotlin 3.1.8")
        analysis.action.assert().startsWith("Align them on one version of tools.jackson:jackson-bom")
        analysis.cause.assert().isSameAs(refused)
    }

    @Test
    fun `Spring Boot should find the initializer and the analyzer`() {
        val factories = javaClass.classLoader.getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION).toList()
            .map { PropertiesLoaderUtils.loadProperties(UrlResource(it)) }
        factories.mapNotNull { it.getProperty(INITIALIZER_KEY) }.joinToString(",")
            .assert().contains(JacksonVersionsInitializer::class.java.name)
        factories.mapNotNull { it.getProperty(ANALYZER_KEY) }.joinToString(",")
            .assert().contains(JacksonVersionFailureAnalyzer::class.java.name)
    }

    private companion object {
        const val CORE = "tools.jackson.core:jackson-core"
        const val DATABIND = "tools.jackson.core:jackson-databind"
        const val KOTLIN = "tools.jackson.module:jackson-module-kotlin"
        const val YAML = "tools.jackson.dataformat:jackson-dataformat-yaml"
        const val INITIALIZER_KEY = "org.springframework.context.ApplicationContextInitializer"
        const val ANALYZER_KEY = "org.springframework.boot.diagnostics.FailureAnalyzer"
        val OWN = setOf(JacksonVersions.RESOURCE)
    }
}
