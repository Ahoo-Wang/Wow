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

package me.ahoo.wow.opentelemetry

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.opentelemetry.eventsourcing.TracingEventStore
import me.ahoo.wow.tck.architecture.DefaultMethodContract
import org.junit.jupiter.api.Test

/**
 * Every `Tracing*` decorator must override every SPI method that has a default body, so a call never falls back
 * to the interface default and bypasses the delegate.
 */
class TracingDecoratorContractTest {
    private val decorators: List<Class<*>> =
        DefaultMethodContract.concreteSubtypes("me.ahoo.wow.opentelemetry", Decorator::class.java)
            .filter { Traced::class.java.isAssignableFrom(it) && it.simpleName.startsWith("Tracing") }
            .filter { it.protectionDomain.codeSource == TracingEventStore::class.java.protectionDomain.codeSource }

    @Test
    fun `scan should find the tracing decorators`() {
        decorators.map { it.simpleName }.assert().contains(
            "TracingEventStore",
            "TracingSnapshotStore",
            "TracingCommandGateway",
            "TracingLocalCommandBus",
            "TracingDistributedCommandBus",
            "TracingLocalEventBus",
            "TracingDistributedEventBus",
            "TracingLocalStateEventBus",
            "TracingDistributedStateEventBus",
        )
    }

    @Test
    fun `tracing decorators should override every SPI default method`() {
        DefaultMethodContract.assertOnlyKnownGaps(decorators, KNOWN_GAPS)
    }

    companion object {
        /**
         * Allowed gaps, emptied by 9.3.0 WP S5. A new entry must name the work item that removes it.
         */
        val KNOWN_GAPS: Map<String, String> = emptyMap()
    }
}
