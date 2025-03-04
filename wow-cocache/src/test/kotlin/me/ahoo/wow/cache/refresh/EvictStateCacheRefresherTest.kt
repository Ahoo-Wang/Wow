package me.ahoo.wow.cache.refresh

import io.mockk.every
import io.mockk.spyk
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.wow.event.StateDomainEventExchange
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class EvictStateCacheRefresherTest {
    private val stateCacheRefresher = EvictStateCacheRefresher<MockStateAggregate, MockStateAggregate>(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        cache = MapClientSideCache()
    )

    @Test
    fun getCache() {
        assertThat(stateCacheRefresher.cache, instanceOf(MapClientSideCache::class.java))
    }

    @Test
    fun invoke() {
        val exchange = spyk<StateDomainEventExchange<MockStateAggregate, Any>> {
            every { state.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
            every { state.deleted } returns true
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }
}
