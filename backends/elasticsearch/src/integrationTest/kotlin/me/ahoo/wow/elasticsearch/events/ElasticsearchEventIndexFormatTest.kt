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

package me.ahoo.wow.elasticsearch.events

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.StringReader

/**
 * The indices' format 2, in which a string is a `text` field only where its schema marks it as prose: an index of
 * format 1 is refused, naming the layout it records, and never converted; the upgrade notes say how to rebuild it.
 */
class ElasticsearchEventIndexFormatTest {
    private suspend fun create(index: String, definition: String) {
        ElasticsearchFixture.client.indices()
            .create(CreateIndexRequest.of { it.index(index).withJson(StringReader(definition)) }).await()
    }

    private fun formatOne(definition: String): String =
        definition.replace("\"wow_format\": 2", "\"wow_format\": 1").also { it.assert().contains("\"wow_format\": 1") }

    @Test
    fun `an events index of format 1 is refused by its Sink, naming the layout it records`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val index = ElasticsearchFixture.names.events(context, "order")
        create(index, formatOne(ElasticsearchEventIndex.DEFINITION))
        assertThrows<IllegalStateException> {
            ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(context, "order").prepare()
        }.message.assert().isEqualTo(
            "The Elasticsearch index [$index] records the layout events/1; this version reads events/2.",
        )
    }

    @Test
    fun `a snapshot index of format 1 is refused, naming the layout it records`(): Unit = runBlocking {
        val index = ElasticsearchFixture.names.snapshots(ElasticsearchFixture.newContext(), "order")
        create(index, formatOne(ElasticsearchSnapshotIndex.DEFINITION))
        listOf(Ddl.AUTO, Ddl.VALIDATE).forEach { ddl ->
            assertThrows<IllegalStateException> {
                ElasticsearchIndices(ElasticsearchFixture.client).ensure(
                    index,
                    ElasticsearchSnapshotIndex.KIND,
                    ElasticsearchSnapshotIndex.FORMAT,
                    ElasticsearchSnapshotIndex.DEFINITION,
                    ddl,
                )
            }.message.assert().isEqualTo(
                "The Elasticsearch index [$index] records the layout snapshot/1; this version reads snapshot/2.",
            )
        }
    }
}
