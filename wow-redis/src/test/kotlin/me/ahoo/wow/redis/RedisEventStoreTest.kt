package me.ahoo.wow.redis

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class RedisEventStoreTest : EventStoreSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    override fun setup() {
        redisInitializer = RedisInitializer()
        super.setup()
    }

    @AfterEach
    fun destroy() {
        redisInitializer.close()
    }

    override fun createEventStore(): EventStore {
        return RedisEventStore(redisInitializer.redisTemplate)
    }

    override fun givenDuplicateRequestIdWhenAppendExpectDuplicateRequestIdException() = Unit

    override fun scanAggregateId() = Unit
}
