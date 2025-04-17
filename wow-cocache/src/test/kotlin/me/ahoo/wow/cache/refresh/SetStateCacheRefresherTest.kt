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
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.StateDomainEventExchange
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.assertj.core.api.Assertions.assertThat
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
    fun getFunctionKind() {
        assertThat(stateCacheRefresher.functionKind).isEqualTo(FunctionKind.STATE_EVENT)
    }

    @Test
    fun getName() {
        assertThat(stateCacheRefresher.name).isEqualTo(StateCacheRefresher<*, *, *>::invoke.name)
    }

    @Test
    fun getProcessor() {
        assertThat(stateCacheRefresher.processor).isEqualTo(stateCacheRefresher)
    }

    @Test
    fun getSupportedTopics() {
        assertThat(stateCacheRefresher.supportedTopics).contains(MOCK_AGGREGATE_METADATA.materialize())
    }

    @Test
    fun getSupportedType() {
        assertThat(stateCacheRefresher.supportedType).isEqualTo(Any::class.java)
    }

    @Test
    fun getAnnotation() {
        assertThat(stateCacheRefresher.getAnnotation(OnEvent::class.java)).isNull()
    }

    @Test
    fun getNamedAggregate() {
        assertThat(stateCacheRefresher.namedAggregate).isEqualTo(MOCK_AGGREGATE_METADATA)
    }

    @Test
    fun getCache() {
        assertThat(stateCacheRefresher.cache).isInstanceOf(MapClientSideCache::class.java)
    }

    @Test
    fun getTtl() {
        assertThat(stateCacheRefresher.ttl).isEqualTo(CoCache.DEFAULT_TTL)
    }

    @Test
    fun getAmplitude() {
        assertThat(stateCacheRefresher.ttlAmplitude).isEqualTo(CoCache.DEFAULT_TTL_AMPLITUDE)
    }

    @Test
    fun invoke() {
        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.state } returns MockStateAggregate(generateGlobalId())
            every { message.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns false
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }

    @Test
    fun invokeIfDeleted() {
        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { message.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns true
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }

    @Test
    fun invokeIfWithTtl() {
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
