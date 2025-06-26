package me.ahoo.wow.cache.refresh

import io.mockk.every
import io.mockk.spyk
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class EvictStateCacheRefresherTest {
    private val stateCacheRefresher = EvictStateCacheRefresher<String, MockStateAggregate, MockStateAggregate>(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        cache = MapClientSideCache()
    )

    @Test
    fun getFunctionKind() {
        stateCacheRefresher.functionKind.assert().isEqualTo(FunctionKind.EVENT)
    }

    @Test
    fun getCache() {
        stateCacheRefresher.cache.assert().isInstanceOf(MapClientSideCache::class.java)
    }

    @Test
    fun invoke() {
        val exchange = spyk<DomainEventExchange<Any>> {
            every { message.aggregateId } returns MOCK_AGGREGATE_METADATA.aggregateId()
        }

        stateCacheRefresher.invoke(exchange).test().verifyComplete()
    }
}
