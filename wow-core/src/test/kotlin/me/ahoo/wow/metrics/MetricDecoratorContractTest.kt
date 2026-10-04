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

package me.ahoo.wow.metrics

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.tck.architecture.DefaultMethodContract
import org.junit.jupiter.api.Test

/**
 * Every `Metric*` decorator must override every SPI method that has a default body, so a call never falls back
 * to the interface default and bypasses the delegate.
 */
class MetricDecoratorContractTest {
    private val decorators: List<Class<*>> =
        DefaultMethodContract.concreteSubtypes("me.ahoo.wow.metrics", Decorator::class.java)
            .filter { Metered::class.java.isAssignableFrom(it) && it.simpleName.startsWith("Metric") }
            .filter { it.protectionDomain.codeSource == MetricEventStore::class.java.protectionDomain.codeSource }

    @Test
    fun `scan should find the metric decorators`() {
        decorators.map { it.simpleName }.assert().contains(
            "MetricEventStore",
            "MetricSnapshotStore",
            "MetricLocalCommandBus",
            "MetricDistributedCommandBus",
            "MetricLocalDomainEventBus",
            "MetricDistributedDomainEventBus",
            "MetricLocalStateEventBus",
            "MetricDistributedStateEventBus",
        )
    }

    @Test
    fun `metric decorators should override every SPI default method`() {
        DefaultMethodContract.assertOnlyKnownGaps(decorators, KNOWN_GAPS)
    }

    companion object {
        /**
         * Gaps that exist today. Each entry names the 9.3.0 work item that removes it; the list may only shrink.
         */
        val KNOWN_GAPS: Map<String, String> = mapOf(
            "MetricEventStore.single(AggregateId,Int)" to S5,
        )

        private const val S5 = "9.3.0 WP S5: decorators forward SPI defaults"
    }
}
