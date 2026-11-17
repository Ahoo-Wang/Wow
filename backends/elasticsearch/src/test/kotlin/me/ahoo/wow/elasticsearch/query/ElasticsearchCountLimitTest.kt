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
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import co.elastic.clients.elasticsearch.core.search.TrackHits
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.PageWindow
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CompletableFuture

/**
 * A bounded count or page total tracks one hit past its limit, so the core reads a larger total as "at least"; at the
 * largest limit, whose next hit an `int` threshold cannot hold, it tracks every hit (#6287).
 */
internal class ElasticsearchCountLimitTest {
    /** The hits a count with [limit], or an offset page with that total limit when [page], asks Elasticsearch to track. */
    private fun tracked(limit: Int, page: Boolean = false): TrackHits = runBlocking {
        val captured = slot<SearchRequest>()
        val client = mockk<ElasticsearchAsyncClient>()
        val response = SearchResponse.of<ObjectNode> { response ->
            response.took(0).timedOut(false)
                .shards { it.total(1).successful(1).failed(0) }
                .hits { it.hits(emptyList()).total { total -> total.value(0).relation(TotalHitsRelation.Eq) } }
        }
        every { client.search(capture(captured), ObjectNode::class.java) } returns
            CompletableFuture.completedFuture(response)
        val documents = object : ElasticsearchQueryDocuments {
            override val tieBreakers = emptyList<String>()
            override val storeFields = emptyList<String>()
            override fun record(source: ObjectNode): ObjectNode = source
        }
        val backend = ElasticsearchQueryBackend(AggregateType("shop", "cart"), client, "index", documents)
        if (page) {
            val window = PageWindow.Offset(0, 1, true, limit)
            backend.page(QueryAdmission.Trusted.list(ListQuery(), nativeSchema()), window).total.assert().isEqualTo(0L)
        } else {
            val count = QueryAdmission.Trusted.count(CountQuery(limit = limit), nativeSchema())
            backend.count(count).assert().isEqualTo(0L)
        }
        checkNotNull(captured.captured.trackTotalHits())
    }

    @Test
    fun `a count at the largest limit tracks every hit`() {
        tracked(Int.MAX_VALUE).enabled().assert().isTrue()
    }

    @Test
    fun `a page total at the largest limit tracks every hit`() {
        tracked(Int.MAX_VALUE, page = true).enabled().assert().isTrue()
    }

    @Test
    fun `a count or a page total below the largest limit tracks one hit past it`() {
        tracked(Int.MAX_VALUE - 1).count().assert().isEqualTo(Int.MAX_VALUE)
        tracked(1000).count().assert().isEqualTo(1001)
        tracked(1000, page = true).count().assert().isEqualTo(1001)
    }
}
