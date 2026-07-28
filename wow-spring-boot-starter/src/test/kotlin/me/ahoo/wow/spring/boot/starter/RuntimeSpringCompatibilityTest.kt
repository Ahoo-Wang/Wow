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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.dispatcher.DomainEventDispatcher
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import me.ahoo.wow.spring.boot.starter.event.EventDispatcherAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.modeling.AggregateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.projection.ProjectionDispatcherAutoConfiguration
import me.ahoo.wow.spring.boot.starter.saga.StatelessSagaAutoConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.context.annotation.Bean
import java.time.Duration

class RuntimeSpringCompatibilityTest {

    @Test
    fun `Wow properties retains legacy constructor ABI`() {
        val legacyConstructor = WowProperties::class.java.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Duration::class.java,
        )

        val properties = legacyConstructor.newInstance(
            true,
            "legacy",
            Duration.ofSeconds(2),
        )

        properties.shutdownQuietPeriod.assert().isEqualTo(DEFAULT_SHUTDOWN_QUIET_PERIOD)
        WowProperties::class.java.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Duration::class.java,
            Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        ).assert().isNotNull()
    }

    @Test
    fun `legacy auto configuration signatures remain callable but are not beans`() {
        EventDispatcherAutoConfiguration::class.java
            .getDeclaredConstructor(WowProperties::class.java)
            .assert()
            .isNotNull()
        AggregateAutoConfiguration::class.java
            .getDeclaredConstructor(WowProperties::class.java)
            .assert()
            .isNotNull()
        ProjectionDispatcherAutoConfiguration::class.java
            .getDeclaredConstructor(WowProperties::class.java)
            .assert()
            .isNotNull()
        StatelessSagaAutoConfiguration::class.java
            .getDeclaredConstructor(WowProperties::class.java)
            .assert()
            .isNotNull()
        SnapshotAutoConfiguration::class.java
            .getDeclaredConstructor(WowProperties::class.java, SnapshotProperties::class.java)
            .assert()
            .isNotNull()

        val legacyFactoryMethods = listOf(
            EventDispatcherAutoConfiguration::class.java.getDeclaredMethod(
                "domainEventDispatcherLauncher",
                DomainEventDispatcher::class.java,
            ),
            AggregateAutoConfiguration::class.java.getDeclaredMethod(
                "aggregateDispatcherLauncher",
                CommandDispatcher::class.java,
            ),
            ProjectionDispatcherAutoConfiguration::class.java.getDeclaredMethod(
                "projectionDispatcherLauncher",
                ProjectionDispatcher::class.java,
            ),
            StatelessSagaAutoConfiguration::class.java.getDeclaredMethod(
                "statelessSagaDispatcherLauncher",
                StatelessSagaDispatcher::class.java,
            ),
            SnapshotAutoConfiguration::class.java.getDeclaredMethod(
                "snapshotDispatcherLauncher",
                SnapshotDispatcher::class.java,
            ),
        )

        legacyFactoryMethods.forEach { method ->
            method.getAnnotation(Bean::class.java).assert().isNull()
        }
    }

    @Test
    fun `dispatcher factories leave destruction ownership to the runtime registry`() {
        val dispatcherFactories = listOf(
            EventDispatcherAutoConfiguration::class.java to "domainEventDispatcher",
            AggregateAutoConfiguration::class.java to "aggregateDispatcher",
            ProjectionDispatcherAutoConfiguration::class.java to "projectionDispatcher",
            StatelessSagaAutoConfiguration::class.java to "statelessSagaDispatcher",
            SnapshotAutoConfiguration::class.java to "snapshotDispatcher",
        )

        dispatcherFactories.forEach { (configurationType, factoryName) ->
            val factoryMethod = configurationType.declaredMethods.single { method ->
                method.name == factoryName
            }

            factoryMethod.getAnnotation(Bean::class.java)
                .destroyMethod
                .assert()
                .isEqualTo(AbstractBeanDefinition.INFER_METHOD)
        }
    }
}
