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
import org.junit.jupiter.api.Test
import java.io.StringReader

/** The events index's format 1 (decision D59): an index created before the tags (decision D259) gets their mapping. */
class ElasticsearchEventIndexFormatTest {
    @Test
    fun `an events index created before the tags gets them when its Sink prepares it`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val index = ElasticsearchFixture.names.events(context, "order")
        val before = ElasticsearchEventIndex.DEFINITION
            .replace(Regex(""""tags": \{[^}]*},\s*"tagsRecorded": \{[^}]*},\s*"""), "")
        before.assert().doesNotContain("tagsRecorded")
        ElasticsearchFixture.client.indices()
            .create(CreateIndexRequest.of { it.index(index).withJson(StringReader(before)) }).await()
        ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(context, "order").prepare()
        val properties = checkNotNull(ElasticsearchIndices(ElasticsearchFixture.client).mapping(index)).properties()
        properties.getValue("tags").isFlattened.assert().isTrue()
        properties.getValue(ElasticsearchEventIndex.TAGS_RECORDED).isBoolean.assert().isTrue()
        // Prepared again, nothing is left to add.
        ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(context, "order").prepare()
    }
}
