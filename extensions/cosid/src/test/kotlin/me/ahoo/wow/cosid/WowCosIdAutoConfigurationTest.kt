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

package me.ahoo.wow.cosid

import me.ahoo.cosid.spring.autoconfigure.CosIdAutoConfiguration
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Properties

/** wow-cosid on CosId's runtime, started by CosId's auto-configuration from `cosid.*`. */
class WowCosIdAutoConfigurationTest {
    private fun runner(vararg properties: String = COSID_PROPERTIES) = cosIdRunner(*properties)
        .withConfiguration(
            AutoConfigurations.of(CosIdAutoConfiguration::class.java, WowCosIdAutoConfiguration::class.java),
        )

    @Configuration(proxyBeanMethods = false)
    class OwnAggregateIds {
        @Bean(WowCosIdAutoConfiguration.AGGREGATE_ID_GENERATOR)
        fun aggregateIds(): AggregateIdGenerator = AggregateIdGenerator { "own" }
    }

    @Test
    fun `CosId's generators should be the host's aggregate ids, and the node's own ids never CosId's`() {
        runner().run { context ->
            context.startupFailure.assert().isNull()
            // The node's own ids (request, command, wait ids) stay the host's UUIDv7: wow-cosid gives none.
            context.getBeansOfType(GlobalIdGenerator::class.java).assert().isEmpty()
            val aggregates = context.getBean(WowCosIdAutoConfiguration.AGGREGATE_ID_GENERATOR)
            aggregates.assert().isInstanceOf(CosIdAggregateIdGenerator::class.java)
            (aggregates as AggregateIdGenerator).next(AggregateType("shop", "order")).length.assert()
                .isEqualTo(RADIX62_LENGTH)
            aggregates.next(AggregateType("shop", "ledger")).toLong().assert().isPositive()
            val source = context.getBean(AggregateIdGeneratorSource::class.java)
            source.find("shop.ledger").assert().isNotNull()
            source.find("missing").assert().isNull()
        }
    }

    @Test
    fun `wow-cosid-aggregate-generator should name CosId's generator of the types without their own`() {
        runner(*COSID_PROPERTIES, "${WowCosIdAutoConfiguration.AGGREGATE_GENERATOR}=counter").run { context ->
            val aggregates = context.getBean(CosIdAggregateIdGenerator::class.java)
            aggregates.fallback.assert().isEqualTo("counter")
            aggregates.next(AggregateType("shop", "cart")).toLong().assert().isPositive()
        }
        runner(*COSID_PROPERTIES, "${WowCosIdAutoConfiguration.AGGREGATE_GENERATOR}= ").run { context ->
            context.getBean(CosIdAggregateIdGenerator::class.java).fallback.assert().isEqualTo("default")
        }
    }

    @Test
    fun `a fallback CosId does not have should fail the start, naming the key`() {
        runner(*COSID_PROPERTIES, "${WowCosIdAutoConfiguration.AGGREGATE_GENERATOR}=missing").run { context ->
            val failure = generateSequence(context.startupFailure) { it.cause }
                .filterIsInstance<WowConfigurationException>().first()
            failure.key.assert().isEqualTo(CosIdProperties.AGGREGATE_GENERATOR)
        }
        // No generator at all, so no default one: the start fails, naming the key and what to configure.
        runner("spring.application.name=wow-cosid-test").run { context ->
            val failure = generateSequence(context.startupFailure) { it.cause }
                .filterIsInstance<WowConfigurationException>().first()
            failure.message.assert().contains("cosid.generators.default")
        }
    }

    @Test
    fun `a time-based generator without a machine should fail CosId's start, naming its machine`() {
        runner("spring.application.name=wow-cosid-test", "cosid.generators.default.strategy=cosid80").run { context ->
            val messages = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            messages.any { "machine" in it }.assert().isTrue()
        }
    }

    @Test
    fun `the application's own aggregate ids should win, and nothing is given without CosId`() {
        runner().withUserConfiguration(OwnAggregateIds::class.java).run { context ->
            context.getBeansOfType(CosIdAggregateIdGenerator::class.java).assert().isEmpty()
        }
        runner("cosid.enabled=false").run { context ->
            context.getBeansOfType(AggregateIdGenerator::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `the host should import it after CosId and before itself`() {
        val loader = javaClass.classLoader
        ImportCandidates.load(AutoConfiguration::class.java, loader).candidates.assert()
            .contains(WowCosIdAutoConfiguration::class.java.name)
        val name = WowCosIdAutoConfiguration::class.java.name
        val metadata = loader.getResources("META-INF/spring-autoconfigure-metadata.properties").toList()
            .map { url -> Properties().apply { url.openStream().use(::load) } }
            .single { it.containsKey(name) }
        val host = "me.ahoo.wow.spring.boot.WowAutoConfiguration"
        metadata.getProperty("$name.AutoConfigureBefore").assert().isEqualTo(host)
        metadata.getProperty("$name.AutoConfigureAfter").assert().isEqualTo(CosIdAutoConfiguration::class.java.name)
    }

    private companion object {
        const val RADIX62_LENGTH = 15
    }
}
