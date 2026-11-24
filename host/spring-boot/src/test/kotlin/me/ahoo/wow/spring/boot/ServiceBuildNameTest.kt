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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ServiceBuild
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.config.WowConfigurationFailureAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.net.URLClassLoader

/**
 * The service's one name is its build's: a node whose class path carries the build of a context it serves
 * (`META-INF/wow/service.json`, which Wow's Gradle plugin writes) refuses to start under another name, naming the
 * property that gave the name it runs under; without the build's resource, as a build without the plugin, it starts.
 */
class ServiceBuildNameTest {
    @TempDir
    lateinit var directory: File

    /** The test's class path with a service module's build beside it, naming [service] for [contexts]. */
    private fun withBuild(service: String, vararg contexts: String): ClassLoader =
        URLClassLoader(arrayOf(build(service, *contexts).toURI().toURL()), javaClass.classLoader)

    private fun build(service: String, vararg contexts: String): File {
        val root = File(directory, service)
        val declared = contexts.joinToString(",") { "\"$it\"" }
        File(root, ServiceBuild.RESOURCE).apply { parentFile.mkdirs() }
            .writeText("""{"name":"$service","contexts":[$declared]}""")
        return root
    }

    private fun start(classLoader: ClassLoader?, vararg properties: Pair<String, Any>): Host = Host(
        SpringApplicationBuilder(HostTestApp::class.java)
            .apply { classLoader?.let { resourceLoader(DefaultResourceLoader(it)) } }
            .properties(hostProperties + ("wow.contexts.include" to BANK) + properties.toMap())
            .run(),
    )

    private fun refusal(classLoader: ClassLoader, vararg properties: Pair<String, Any>): WowConfigurationException {
        val failure = assertThrows<Exception> { start(classLoader, *properties).close() }
        return generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
    }

    private val resource: String get() = File(directory, "bank-service/${ServiceBuild.RESOURCE}").toURI().toString()

    @Test
    fun `a node named by spring-application-name otherwise than its build should not start, saying which key to set`() {
        val refused = refusal(withBuild("bank-service", BANK), "spring.application.name" to "bank")
        val analysis = WowConfigurationFailureAnalyzer().analyze(refused)!!
        analysis.description.assert().isEqualTo(
            "Wow's configuration is invalid: The service runs as bank (spring.application.name), but its build " +
                "names it bank-service ($resource): peers, its chart and its baseline know it by the name its build " +
                "gives it.\n\n    Property: spring.application.name\n    Value: bank",
        )
        analysis.action.assert()
            .isEqualTo(
                "Set spring.application.name: bank-service, or name the service bank in its build (wow { service }).",
            )
    }

    @Test
    fun `a node named by wow-service otherwise than its build should not start, naming wow-service`() {
        val refused = refusal(withBuild("bank-service", BANK), "wow.service" to "bank")
        refused.key.assert().isEqualTo("wow.service")
        refused.value.assert().isEqualTo("bank")
        refused.fix.assert().startsWith("Set wow.service: bank-service,")
    }

    @Test
    fun `a node should start under its build's name, and beside the build of a context it does not serve`() {
        start(withBuild("bank-service", BANK)).use { it.node.service.assert().isEqualTo("bank-service") }
        start(withBuild("ledger-service", "ledger"), "spring.application.name" to "bank").use {
            it.node.service.assert().isEqualTo("bank")
        }
    }

    @Test
    fun `a node with no build on its class path should start under any name, unchecked`() {
        start(null, "spring.application.name" to "bank").use { it.node.service.assert().isEqualTo("bank") }
    }
}
