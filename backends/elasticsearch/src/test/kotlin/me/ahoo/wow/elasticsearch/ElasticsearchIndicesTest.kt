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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesAsyncClient
import co.elastic.clients.elasticsearch.indices.GetIndexRequest
import co.elastic.clients.elasticsearch.indices.GetIndexResponse
import co.elastic.clients.util.ObjectBuilder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/** How [ElasticsearchIndices] reads a name: a failure other than a missing index is the cluster's, as it is. */
class ElasticsearchIndicesTest {
    @Test
    fun `a read of the name the cluster refuses should fail as its answer, never as a missing index`() {
        val refused = ElasticsearchException(
            "indices.get",
            ErrorResponse.of {
                it.status(FORBIDDEN).error { error -> error.type("security_exception").reason("no read of the index") }
            },
        )
        val indices = mockk<ElasticsearchIndicesAsyncClient>()
        every {
            indices.get(any<Function<GetIndexRequest.Builder, ObjectBuilder<GetIndexRequest>>>())
        } returns CompletableFuture.failedFuture<GetIndexResponse>(refused)
        val client = mockk<ElasticsearchAsyncClient> { every { indices() } returns indices }
        val failure = runCatching {
            runBlocking {
                ElasticsearchIndices(client).ensure(
                    "wow_snapshot__shop__order",
                    ElasticsearchSnapshotIndex.KIND,
                    ElasticsearchSnapshotIndex.FORMAT,
                    ElasticsearchSnapshotIndex.DEFINITION,
                    Ddl.VALIDATE,
                )
            }
        }.exceptionOrNull()
        failure.assert().isSameAs(refused)
    }

    private companion object {
        const val FORBIDDEN = 403
    }
}
