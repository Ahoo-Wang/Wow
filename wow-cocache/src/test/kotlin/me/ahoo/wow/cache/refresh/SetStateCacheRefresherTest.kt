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

package me.ahoo.wow.cache.refresh

import io.mockk.every
import io.mockk.spyk
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.StateDomainEventExchange
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SetStateCacheRefresherTest {
    private val stateCacheRefresher = SetStateCacheRefresher<String, MockStateAggregate, MockStateAggregate>(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        stateToCacheDataConverter = {
            it.state
        },
        cache = MapClientSideCache()
    )

    @Test
    fun `should return STATE_EVENT as function kind`() {
        stateCacheRefresher.functionKind.assert().isEqualTo(FunctionKind.STATE_EVENT)
    }

    @Test
    fun `should return invoke function name`() {
        stateCacheRefresher.name.assert().isEqualTo(StateCacheRefresher<*, *, *>::invoke.name)
    }

    @Test
    fun `should return itself as processor`() {
        stateCacheRefresher.processor.assert().isEqualTo(stateCacheRefresher)
    }

    @Test
    fun `should contain named aggregate in supported topics`() {
        stateCacheRefresher.supportedTopics.assert().contains(MOCK_AGGREGATE_METADATA.materialize())
    }

    @Test
    fun `should return Any class as supported type`() {
        stateCacheRefresher.supportedType.assert().isEqualTo(Any::class.java)
    }

    @Test
    fun `should return null for OnEvent annotation`() {
        stateCacheRefresher.getAnnotation(OnEvent::class.java).assert().isNull()
    }

    @Test
    fun `should return mock aggregate metadata as named aggregate`() {
        stateCacheRefresher.namedAggregate.assert().isEqualTo(MOCK_AGGREGATE_METADATA)
    }

    @Test
    fun `should return MapClientSideCache instance`() {
        stateCacheRefresher.cache.assert().isInstanceOf(MapClientSideCache::class.java)
    }

    @Test
    fun `should return default TTL`() {
        stateCacheRefresher.ttl.assert().isEqualTo(CoCache.DEFAULT_TTL)
    }

    @Test
    fun `should return default TTL amplitude`() {
        stateCacheRefresher.ttlAmplitude.assert().isEqualTo(CoCache.DEFAULT_TTL_AMPLITUDE)
    }

    @Test
    fun `should complete when invoking with non-deleted state`() {
        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.state } returns MockStateAggregate(generateGlobalId())
            every { message.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns false
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }

    @Test
    fun `should complete when invoking with deleted state`() {
        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { message.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns true
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }

    @Test
    fun `should complete when invoking with custom TTL`() {
        val stateCacheRefresher = SetStateCacheRefresher<String, MockStateAggregate, MockStateAggregate>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            stateToCacheDataConverter = {
                it.state
            },
            cache = MapClientSideCache(),
            ttl = 600
        )

        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.state } returns MockStateAggregate(generateGlobalId())
            every { message.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns false
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }
}
