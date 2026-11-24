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

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test

/**
 * Review r1 (transport): `ddl: none` "creates and checks nothing" (the configuration reference). The event sink's
 * `_bulk` of `create`s to an index that does not exist has Elasticsearch create it (`action.auto_create_index`,
 * true by default) with a dynamic mapping: `events` an object, not the nested field the `events` queries read.
 */
class ReviewDdlNoneTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "unmade")
    private val index = ElasticsearchFixture.names.events(type.context, type.name)

    private fun record(version: Int) = StateEventRecord(
        ref = type.ref("tenant", "a-1"),
        version = version,
        commandId = "c-$version",
        requestId = "r-$version",
        commandType = "${type.context}.${type.name}.Act",
        commitTime = version.toLong(),
        events = listOf(
            StateEventPayload("${type.context}.${type.name}.Acted", 1, WowJson.mapper.readTree("""{"n":1}""")),
        ),
    )

    @Test
    fun `a sink told to create nothing should not have its missing index created by its write`(): Unit =
        runBlocking {
            val sink = ElasticsearchEventSinks.of(ElasticsearchFixture.restClient, ddl = Ddl.NONE)
                .sinkOf(type.context, type.name)
            runCatching { sink.write(listOf(record(1))) }
            // Fails: the write created [index], with the mapping Elasticsearch guessed.
            ElasticsearchFixture.client.indices().exists { it.index(index) }.await().value().assert().isFalse()
        }
}
