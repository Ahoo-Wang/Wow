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

package me.ahoo.wow.reactor

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.scheduler.DefaultAggregateSchedulerSupplier
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class SchedulerTest {

    @Test
    fun `aggregate scheduler supplier reuses schedulers per aggregate`() {
        val supplier = DefaultAggregateSchedulerSupplier(name = "test-scheduler", parallelism = 1)
        val order = "wow-core-test.messaging_aggregate".toNamedAggregate()
        val other = "wow-core-test.command_aggregate".toNamedAggregate()

        val first = supplier.getOrInitialize(order)
        val second = supplier.getOrInitialize(order)
        val third = supplier.getOrInitialize(other)

        second.assert().isSameAs(first)
        third.assert().isNotSameAs(first)

        StepVerifier.create(supplier.stopGracefully())
            .verifyComplete()
    }

    @Test
    fun `stopGracefully disposes cached schedulers and clears the cache`() {
        val supplier = DefaultAggregateSchedulerSupplier(name = "test-scheduler", parallelism = 1)
        val aggregate = "wow-core-test.messaging_aggregate".toNamedAggregate()
        val scheduler = supplier.getOrInitialize(aggregate)

        StepVerifier.create(supplier.stopGracefully())
            .verifyComplete()

        scheduler.isDisposed.assert().isTrue()
        supplier.getOrInitialize(aggregate).assert().isNotSameAs(scheduler)

        StepVerifier.create(supplier.stopGracefully())
            .verifyComplete()
    }
}
