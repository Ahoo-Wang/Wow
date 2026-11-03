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

/** The events index's format 1 (decision D59): an index created before the access attributes gets their mapping. */
class ElasticsearchEventIndexFormatTest {
    @Test
    fun `an events index created before the access attributes gets them when its Sink prepares it`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val index = ElasticsearchFixture.names.events(context, "order")
        val before = ElasticsearchEventIndex.DEFINITION
            .replace(Regex(""""dynamic_templates":\s*\[[^\]]*]\s*,\s*"""), "")
            .replace(Regex(""""access": \{[^}]*},\s*"accessVersion": \{[^}]*},\s*"""), "")
        before.assert().doesNotContain("accessVersion").doesNotContain("access_strings")
        ElasticsearchFixture.client.indices()
            .create(CreateIndexRequest.of { it.index(index).withJson(StringReader(before)) }).await()
        ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(context, "order").prepare()
        val properties = checkNotNull(ElasticsearchIndices(ElasticsearchFixture.client).mapping(index)).properties()
        properties.getValue("access").isObject.assert().isTrue()
        properties.getValue(ElasticsearchEventIndex.ACCESS_VERSION).isInteger.assert().isTrue()
        // Prepared again, nothing is left to add.
        ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(context, "order").prepare()
    }
}
