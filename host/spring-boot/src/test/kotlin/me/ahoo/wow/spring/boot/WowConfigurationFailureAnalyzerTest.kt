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
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.core.io.UrlResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import org.springframework.core.io.support.SpringFactoriesLoader

class WowConfigurationFailureAnalyzerTest {
    private val failure = WowConfigurationException(
        "wow.cluster.router",
        "partitioned",
        "wow.cluster.router=partitioned routes across nodes, which the memory backend cannot serve.",
        "Back Wow with PostgreSQL or MongoDB, or route locally.",
    )

    @Test
    fun `a configuration failure should name the key, its value and the fix`() {
        failure.message.assert().isEqualTo("${failure.reason} ${failure.fix}")
        val analysis = WowConfigurationFailureAnalyzer().analyze(BeanCreationException("wowNode", "failed", failure))!!
        analysis.description.assert().contains(failure.reason, "Property: wow.cluster.router", "Value: partitioned")
        analysis.action.assert().isEqualTo(failure.fix)
        analysis.cause.assert().isSameAs(failure)
    }

    @Test
    fun `a key that is not set should have no value line`() {
        val unset = WowConfigurationException("wow.kafka.bootstrap-servers", null, "It is not set.", "Set it.")
        WowConfigurationFailureAnalyzer().analyze(unset)!!.description.assert().doesNotContain("Value:")
    }

    @Test
    fun `Spring Boot should find the analyzer`() {
        // Each jar's spring.factories on its own: merged, one jar's list would replace another's.
        val analyzers = javaClass.classLoader.getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION).toList()
            .mapNotNull { PropertiesLoaderUtils.loadProperties(UrlResource(it)).getProperty(ANALYZER_KEY) }
        analyzers.joinToString(",").assert().contains(WowConfigurationFailureAnalyzer::class.java.name)
    }

    @Test
    fun `a check that fails should throw the configuration failure`() {
        assertThrows<WowConfigurationException> { configCheck(false, "wow.x", 1, "Fix it.") { "Wrong." } }
            .key.assert().isEqualTo("wow.x")
        configNotNull("set", "wow.x", "Fix it.") { "Wrong." }.assert().isEqualTo("set")
        assertThrows<WowConfigurationException> { configNotNull<String>(null, "wow.x", "Fix it.", "bad") { "Wrong." } }
            .value.assert().isEqualTo("bad")
    }

    private companion object {
        val ANALYZER_KEY: String = FailureAnalyzer::class.java.name
    }
}
