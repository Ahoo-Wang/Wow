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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPager
import me.ahoo.wow.elasticsearch.query.aggregation.epochFactors
import org.junit.jupiter.api.Test
import java.time.Duration

/** The pagers refuse a batch or a keep-alive no search can run with. */
class ElasticsearchPagersTest {
    private val client = mockk<ElasticsearchAsyncClient>()

    @Test
    fun `a batch is between one and the search window and a keep-alive at least a millisecond`() {
        assertThrownBy<IllegalArgumentException> { ElasticsearchQueryPager(client, "index", batchSize = 0) }
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchQueryPager(client, "index", batchSize = DEFAULT_SEARCH_BATCH_SIZE + 1)
        }
        assertThrownBy<IllegalArgumentException> { ElasticsearchQueryPager(client, "index", keepAlive = Duration.ZERO) }
        assertThrownBy<IllegalArgumentException> { ElasticsearchAggregationPager(client, "index", batchSize = 0) }
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchAggregationPager(client, "index", keepAlive = Duration.ZERO)
        }
    }

    @Test
    fun `every epoch unit converts to milliseconds`() {
        java.util.concurrent.TimeUnit.entries.associateWith { it.epochFactors }.values.toSet().size.assert().isEqualTo(
            7
        )
        java.util.concurrent.TimeUnit.SECONDS.epochFactors.assert().isEqualTo(1_000L to 1L)
        java.util.concurrent.TimeUnit.NANOSECONDS.epochFactors.assert().isEqualTo(1L to 1_000_000L)
    }

    @Test
    fun `a keep-alive is written in minutes when it is whole minutes`() {
        ElasticsearchPointInTime(client, "index", Duration.ofMinutes(2)).keepAliveValue.assert().isEqualTo("2m")
        ElasticsearchPointInTime(client, "index", Duration.ofMillis(1500)).keepAliveValue.assert().isEqualTo("1500ms")
    }
}
