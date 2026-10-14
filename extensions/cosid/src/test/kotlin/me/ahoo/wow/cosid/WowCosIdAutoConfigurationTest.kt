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

import me.ahoo.cosid.CosId
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.cosid.provider.IdGeneratorProvider
import me.ahoo.cosid.snowflake.MillisecondSnowflakeId
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Properties

class WowCosIdAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WowCosIdAutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    class RunsCosId {
        @Bean
        fun idGeneratorProvider(): IdGeneratorProvider = DefaultIdGeneratorProvider().apply {
            share = MillisecondSnowflakeId(9)
            set(CosId.COSID, ClockSyncCosIdGenerator(Radix62CosIdGenerator(4)))
            set("snowflake", MillisecondSnowflakeId(2))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class OwnGenerators {
        @Bean
        fun globalIds(): GlobalIdGenerator = GlobalIdGenerator { "own" }

        @Bean(WowCosIdAutoConfiguration.AGGREGATE_ID_GENERATOR)
        fun aggregateIds(): AggregateIdGenerator = AggregateIdGenerator { "own" }
    }

    @Test
    fun `CosId's generators should be the host's when the application runs CosId`() {
        runner.withUserConfiguration(RunsCosId::class.java).run { context ->
            val global = context.getBean(GlobalIdGenerator::class.java) as CosIdGlobalIdGenerator
            global.name.assert().isEqualTo(CosId.COSID)
            global.generator.assert().isInstanceOf(ClockSyncCosIdGenerator::class.java)
            val aggregates = context.getBean(WowCosIdAutoConfiguration.AGGREGATE_ID_GENERATOR)
            aggregates.assert().isInstanceOf(CosIdAggregateIdGenerator::class.java)
            val id = (aggregates as AggregateIdGenerator).next(AggregateType("shop", "order"))
            Radix62CosIdGenerator(4).stateParser.asState(id).machineId.assert().isEqualTo(4)
            context.getBean(AggregateIdGeneratorSource::class.java).find("snowflake").assert().isNotNull()
        }
    }

    @Test
    fun `wow-cosid-global-generator should name CosId's generator of the global ids`() {
        runner.withUserConfiguration(RunsCosId::class.java)
            .withPropertyValues("${WowCosIdAutoConfiguration.GLOBAL_GENERATOR}=snowflake")
            .run { context ->
                val global = context.getBean(GlobalIdGenerator::class.java) as CosIdGlobalIdGenerator
                global.generator.assert().isInstanceOf(MillisecondSnowflakeId::class.java)
                val aggregates = context.getBean(CosIdAggregateIdGenerator::class.java)
                aggregates.next(AggregateType("shop", "cart")).assert().isNotBlank()
            }
        runner.withUserConfiguration(RunsCosId::class.java)
            .withPropertyValues("${WowCosIdAutoConfiguration.GLOBAL_GENERATOR}= ")
            .run { context -> context.getBean(CosIdGlobalIdGenerator::class.java).name.assert().isEqualTo(CosId.COSID) }
    }

    @Test
    fun `the application's own generators should win, and nothing is given without CosId`() {
        runner.withUserConfiguration(RunsCosId::class.java, OwnGenerators::class.java).run { context ->
            context.getBeansOfType(CosIdGlobalIdGenerator::class.java).assert().isEmpty()
            context.getBeansOfType(CosIdAggregateIdGenerator::class.java).assert().isEmpty()
        }
        runner.run { context ->
            context.getBeansOfType(GlobalIdGenerator::class.java).assert().isEmpty()
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
        metadata.getProperty("$name.AutoConfigureAfter").split(',').assert()
            .contains("me.ahoo.cosid.spring.boot.starter.CosIdAutoConfiguration")
    }
}
