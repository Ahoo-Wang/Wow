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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MetricComponentDecoratorTest {
    @Test
    fun `descriptor should supply bounded defaults`() {
        val descriptor = TestMetricComponentDecorator("test").defaultDescriptor()

        descriptor.context.assert().isEqualTo(MetricDescriptor.NONE)
        descriptor.aggregate.assert().isEqualTo(MetricDescriptor.NONE)
        descriptor.message.assert().isEqualTo(MetricDescriptor.NONE)
        descriptor.processor.assert().isEqualTo(MetricDescriptor.NONE)
        descriptor.subscriber.assert().isEqualTo(MetricDescriptor.NONE)
    }

    @Test
    fun `decorator should reject a blank source`() {
        assertThrows<IllegalArgumentException> {
            TestMetricComponentDecorator(" ")
        }
    }
}

private class TestMetricComponentDecorator(
    source: String,
) : MetricComponentDecorator<Any>(Any(), WowMetrics.NONE, source) {
    fun defaultDescriptor(): MetricDescriptor = descriptor(
        component = "test",
        operation = "default",
    )
}
