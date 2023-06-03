package me.ahoo.wow.redis

import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotRepositorySpec
import org.junit.jupiter.api.BeforeEach

class RedisSnapshotRepositoryTest : SnapshotRepositorySpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createSnapshotRepository(): SnapshotRepository {
        return RedisSnapshotRepository(redisInitializer.redisTemplate)
    }
}
