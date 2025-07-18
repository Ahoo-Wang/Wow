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

package me.ahoo.wow.spring.boot.starter.prepare

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.infra.prepare.proxy.PrepareKeyProxyFactory
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandGatewayAutoConfiguration
import me.ahoo.wow.spring.boot.starter.compensation.CompensationAutoConfiguration
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.event.EventAutoConfiguration
import me.ahoo.wow.spring.boot.starter.event.EventDispatcherAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaAutoConfiguration
import me.ahoo.wow.spring.boot.starter.metrics.MetricsAutoConfiguration
import me.ahoo.wow.spring.boot.starter.modeling.AggregateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.mongo.MongoEventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.opentelemetry.WowOpenTelemetryAutoConfiguration
import me.ahoo.wow.spring.boot.starter.projection.ProjectionDispatcherAutoConfiguration
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import me.ahoo.wow.spring.boot.starter.r2dbc.R2dbcAutoConfiguration
import me.ahoo.wow.spring.boot.starter.saga.StatelessSagaAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.WowWebClientAutoConfiguration
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PrepareAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withBean(PrepareKeyFactory::class.java, {
                mockk()
            })
            .withUserConfiguration(PrepareAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(PrepareKeyProxyFactory::class.java)
                    .hasSingleBean(PrepareProperties::class.java)
            }
    }

    @Test
    fun contextLoadsWithAutoConfiguration() {
        val prepareKeyFactory = object : PrepareKeyFactory {
            override fun <V : Any> create(
                name: String,
                valueClass: Class<V>
            ): PrepareKey<V> {
                return mockk()
            }
        }
        contextRunner
            .enableWow()
            .withBean(PrepareKeyFactory::class.java, {
                prepareKeyFactory
            })
            .withUserConfiguration(EnablePrepareConfiguration::class.java)
            .withUserConfiguration(PrepareAutoConfiguration::class.java)
            .withClassLoader(this.javaClass.classLoader)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(PrepareKeyProxyFactory::class.java)
                    .hasSingleBean(PrepareProperties::class.java)
                    .hasSingleBean(MockPrepareKey::class.java)
                val mockPrepareKey = context.getBean<MockPrepareKey>()
                mockPrepareKey.assert().isNotNull()
            }
    }
}

@SpringBootApplication(
    exclude = [
        CommandAutoConfiguration::class,
        EventAutoConfiguration::class,
        EventDispatcherAutoConfiguration::class,
        AggregateAutoConfiguration::class,
        EventSourcingAutoConfiguration::class,
        EventStoreAutoConfiguration::class,
        StateAutoConfiguration::class,
        SnapshotAutoConfiguration::class,
        MongoEventSourcingAutoConfiguration::class,
        KafkaAutoConfiguration::class,
        ProjectionDispatcherAutoConfiguration::class,
        StatelessSagaAutoConfiguration::class,
        MetricsAutoConfiguration::class,
        CommandGatewayAutoConfiguration::class,
        WowOpenTelemetryAutoConfiguration::class,
        R2dbcAutoConfiguration::class,
        org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration::class,
        OpenAPIAutoConfiguration::class,
        WebFluxAutoConfiguration::class,
        WowWebClientAutoConfiguration::class,
        CompensationAutoConfiguration::class,
        QueryAutoConfiguration::class,
    ]
)
class EnablePrepareConfiguration

@PreparableKey
interface MockPrepareKey : PrepareKey<String>
