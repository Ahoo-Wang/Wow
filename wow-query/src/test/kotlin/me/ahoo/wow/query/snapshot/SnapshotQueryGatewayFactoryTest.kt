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

package me.ahoo.wow.query.snapshot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class SnapshotQueryGatewayFactoryTest {
    private val handler = mockk<SnapshotQueryHandler>()
    private val backend = mockk<SnapshotQueryService<Any>> {
        every { name } returns "backend"
    }
    private val backendFactory = mockk<SnapshotQueryServiceFactory> {
        every { create<Any>(any()) } returns backend
    }
    private val backendProvider = CachingSnapshotQueryBackendProvider(backendFactory)
    private val factory = DefaultSnapshotQueryGatewayFactory(handler, backendProvider)

    @Test
    fun `should create cached gateway preserving backend name`() {
        val first = factory.create<Any>(MOCK_AGGREGATE_METADATA)
        val second = factory.create<Any>(MOCK_AGGREGATE_METADATA)

        first.assert().isSameAs(second)
        first.name.assert().isEqualTo("backend")
        verify(exactly = 1) { backendFactory.create<Any>(any()) }
    }

    @Test
    fun `should delegate query through handler`() {
        val condition = Condition.ALL
        every { handler.count(any<NamedAggregate>(), condition) } returns Mono.just(1)

        factory.create<Any>(MOCK_AGGREGATE_METADATA)
            .count(condition)
            .test()
            .expectNext(1)
            .verifyComplete()

        verify(exactly = 1) { handler.count(any<NamedAggregate>(), condition) }
    }
}
