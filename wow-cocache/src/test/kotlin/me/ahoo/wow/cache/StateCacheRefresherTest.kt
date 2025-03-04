package me.ahoo.wow.cache

import io.mockk.every
import io.mockk.spyk
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.StateDomainEventExchange
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class StateCacheRefresherTest {
    private val stateCacheRefresher = StateCacheRefresher<MockStateAggregate, MockStateAggregate>(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        stateToCacheDataConverter = StateToCacheDataConverter.identity(),
        cache = MapClientSideCache(),
        mode = RefreshMode.SET
    )

    @Test
    fun getFunctionKind() {
        assertThat(stateCacheRefresher.functionKind, equalTo(FunctionKind.STATE_EVENT))
    }

    @Test
    fun getName() {
        assertThat(stateCacheRefresher.name, equalTo(StateCacheRefresher<*, *>::invoke.name))
    }

    @Test
    fun getProcessor() {
        assertThat(stateCacheRefresher.processor, equalTo(stateCacheRefresher))
    }

    @Test
    fun getSupportedTopics() {
        assertThat(stateCacheRefresher.supportedTopics, equalTo(setOf(MOCK_AGGREGATE_METADATA.materialize())))
    }

    @Test
    fun getSupportedType() {
        assertThat(stateCacheRefresher.supportedType, equalTo(Any::class.java))
    }

    @Test
    fun getAnnotation() {
        assertThat(stateCacheRefresher.getAnnotation(OnEvent::class.java), nullValue())
    }

    @Test
    fun getNamedAggregate() {
        assertThat(stateCacheRefresher.namedAggregate, equalTo(MOCK_AGGREGATE_METADATA))
    }

    @Test
    fun getTtl() {
        assertThat(stateCacheRefresher.ttl, nullValue())
    }

    @Test
    fun invoke() {
        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.state } returns MockStateAggregate(generateGlobalId())
            every { state.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns false
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }

    @Test
    fun invokeIfDeleted() {
        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns true
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }

    @Test
    fun invokeIfWithTtl() {
        val stateCacheRefresher = StateCacheRefresher<MockStateAggregate, MockStateAggregate>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            stateToCacheDataConverter = StateToCacheDataConverter.identity(),
            cache = MapClientSideCache(),
            ttl = 600
        )

        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.state } returns MockStateAggregate(generateGlobalId())
            every { state.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns false
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }

    @Test
    fun invokeIfEvictMode() {
        val stateCacheRefresher = StateCacheRefresher<MockStateAggregate, MockStateAggregate>(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            stateToCacheDataConverter = StateToCacheDataConverter.identity(),
            cache = MapClientSideCache(),
            mode = RefreshMode.EVICT
        )

        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }
}
