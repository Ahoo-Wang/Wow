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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MetricDescriptorTest {
    private val descriptor = MetricDescriptor(
        component = "event_store",
        operation = "append",
        context = "sales",
        aggregate = "Order",
        message = "OrderCreated",
        processor = "OrderProcessor",
        source = "mongo",
        subscriber = "projection-worker",
    )

    @Test
    fun `descriptor should expose every bounded identity tag`() {
        descriptor.baseTags().associate { it.key to it.value }
            .assert()
            .isEqualTo(
                mapOf(
                    "component" to "event_store",
                    "operation" to "append",
                    "context" to "sales",
                    "aggregate" to "Order",
                    "message" to "OrderCreated",
                    "processor" to "OrderProcessor",
                    "source" to "mongo",
                    "subscriber" to "projection-worker",
                )
            )

        val terminalTags = descriptor.terminalTags(MetricOutcome.ERROR, "IllegalStateException")
            .associate { it.key to it.value }
        terminalTags["outcome"].assert().isEqualTo("error")
        terminalTags["exception"].assert().isEqualTo("IllegalStateException")
    }

    @Test
    fun `descriptor should reject blank tag values`() {
        val invalidDescriptors = listOf<() -> MetricDescriptor>(
            { descriptor.copy(component = " ") },
            { descriptor.copy(operation = " ") },
            { descriptor.copy(context = " ") },
            { descriptor.copy(aggregate = " ") },
            { descriptor.copy(message = " ") },
            { descriptor.copy(processor = " ") },
            { descriptor.copy(source = " ") },
            { descriptor.copy(subscriber = " ") },
        )

        invalidDescriptors.forEach { createInvalid ->
            assertThrows(IllegalArgumentException::class.java) {
                createInvalid()
            }
        }
    }
}
