package me.ahoo.wow.cache.refresh

import io.mockk.every
import io.mockk.spyk
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class EvictStateCacheRefresherTest {
    private val stateCacheRefresher = EvictStateCacheRefresher<String, MockStateAggregate, MockStateAggregate>(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        cache = MapClientSideCache()
    )

    @Test
    fun getFunctionKind() {
        assertThat(stateCacheRefresher.functionKind, equalTo(FunctionKind.EVENT))
    }

    @Test
    fun getCache() {
        assertThat(stateCacheRefresher.cache, instanceOf(MapClientSideCache::class.java))
    }

    @Test
    fun invoke() {
        val exchange = spyk<DomainEventExchange<Any>> {
            every { message.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }
}
