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
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.config.ConnectionDefaultsEnvironmentPostProcessor
import me.ahoo.wow.spring.boot.observability.HealthGroupsEnvironmentPostProcessor
import me.ahoo.wow.spring.boot.security.CoSecModeEnvironmentPostProcessor
import me.ahoo.wow.spring.boot.store.MongoClientAutoConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * A setting the host's environment post-processors read that does not parse: each adds nothing and refuses nothing, and
 * the setting's owner refuses it once Spring Boot has configured the application's logging, so the failure analysis is
 * a line of the application's own log (its name in the line), not of the console before logging exists.
 */
@ExtendWith(OutputCaptureExtension::class)
class EarlyRefusalTest {
    private fun refused(setting: Pair<String, Any>, output: CapturedOutput, vararg analysis: String): Throwable {
        val failure = assertThrows<Exception> { startHost(setting) }
        output.out.assert().contains("APPLICATION FAILED TO START", *analysis)
        output.out.assert().containsPattern("ERROR .*--- \\[bank-service] .*LoggingFailureAnalysisReporter")
        return failure
    }

    @Test
    fun `a wow setting that does not bind should be refused by the properties' binding, on the application's log`(
        output: CapturedOutput,
    ) {
        refused("wow.memory.topic-retention" to "0", output, "Property: wow.memory.topic-retention", "Value: \"0\"")
    }

    @Test
    fun `a CoSec mode that does not bind should be refused by the properties' binding, on the application's log`(
        output: CapturedOutput,
    ) {
        refused("wow.cosec.mode" to "gatewayy", output, "Property: wow.cosec.mode", "Value: \"gatewayy\"")
    }

    /**
     * Actuator refuses no value of the key: its probes are on only for `true` (`@ConditionalOnBooleanProperty`), so
     * with any other value they are off, and the node's groups, which name the probes' indicators, are not added
     * either.
     */
    @Test
    fun `a probes setting that is not a boolean should turn the probes off as Actuator does, adding no groups`(
        output: CapturedOutput,
    ) {
        startHost(PROBES to "maybe").use { host ->
            host.context.containsBean("livenessStateHealthIndicator").assert().isFalse()
            host.context.environment.propertySources.contains(HealthGroupsEnvironmentPostProcessor.SOURCE).assert()
                .isFalse()
        }
        output.out.assert().doesNotContain("APPLICATION FAILED TO START")
    }

    @Test
    fun `a MongoDB URI that does not parse should be refused naming spring-mongodb-uri, on the application's log`(
        output: CapturedOutput,
    ) {
        val failure = refused(MONGO_URI to "mongodb://localhost/sh%zzop", output, "Property: $MONGO_URI")
        val refusal = generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().single()
        refusal.key.assert().isEqualTo(MONGO_URI)
        refusal.reason.assert().contains("Illegal hex characters in escape (%) pattern")
        // The URI may hold a password: the analysis names the key, never the value.
        output.out.assert().doesNotContain("Value: \"mongodb://")
    }

    @Test
    fun `each post-processor should add nothing for a value it cannot read, and throw nothing`() {
        val wowReaders = listOf(
            ShutdownTimeoutEnvironmentPostProcessor.SOURCE,
            CoSecModeEnvironmentPostProcessor.SOURCE,
            HealthGroupsEnvironmentPostProcessor.SOURCE,
        )
        // One value at a time, so a post-processor that returns early on one does not hide its read of another; with
        // each, the defaults of the post-processors that read it.
        mapOf(
            ("wow.memory.topic-retention" to "0") to wowReaders,
            ("wow.cosec.mode" to "gatewayy") to wowReaders,
            (PROBES to "maybe") to listOf(HealthGroupsEnvironmentPostProcessor.SOURCE),
            (MONGO_URI to "mongodb://localhost/sh%zzop") to listOf(ConnectionDefaultsEnvironmentPostProcessor.SOURCE),
        ).forEach { (unreadable, readers) ->
            val environment = StandardEnvironment()
            environment.propertySources.addFirst(MapPropertySource("app", mapOf(unreadable)))
            listOf(
                ShutdownTimeoutEnvironmentPostProcessor(),
                CoSecModeEnvironmentPostProcessor(),
                HealthGroupsEnvironmentPostProcessor(),
                ConnectionDefaultsEnvironmentPostProcessor(),
            ).forEach { processor ->
                assertDoesNotThrow("$processor on $unreadable") {
                    processor.postProcessEnvironment(environment, SpringApplication())
                }
            }
            environment.propertySources.map { it.name }.assert().describedAs("$unreadable").doesNotContainAnyElementsOf(
                readers,
            )
        }
    }

    @Test
    fun `a MongoDB URI whose path does not decode should name no database`() {
        ConnectionDefaultsEnvironmentPostProcessor.databaseOf("mongodb://localhost/sh%zzop").assert().isNull()
        ConnectionDefaultsEnvironmentPostProcessor.databaseOf("mongodb://localhost/sh%6Fp").assert().isEqualTo("shop")
    }

    private companion object {
        const val PROBES = HealthGroupsEnvironmentPostProcessor.PROBES
        const val MONGO_URI = MongoClientAutoConfiguration.URI
    }
}
