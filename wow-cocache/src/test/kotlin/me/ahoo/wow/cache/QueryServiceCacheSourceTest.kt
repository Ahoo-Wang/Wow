package me.ahoo.wow.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono

class QueryServiceCacheSourceTest {
    @Test
    fun load() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "test"
        }
        val queryApiCacheSource = spyk<SnapshotQueryService<String>> {
            every { single(any()) } returns snapshot.toMono()
        }
        val queryServiceCacheSource = QueryServiceCacheSource(
            queryApiCacheSource,
            { it },
            LoadCacheSourceConfiguration.DEFAULT
        )

        val cacheValue = queryServiceCacheSource.loadCacheValue("test")
        assertThat(cacheValue, equalTo(DefaultCacheValue.forever("test")))
    }
}
