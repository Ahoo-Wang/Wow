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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test

/**
 * The events index is an alias of one concrete index, written with `require_alias` (#6785): a write to a missing alias
 * is refused, naming it, and never has Elasticsearch create an index with a mapping it guesses.
 */
class ElasticsearchEventSinkAliasTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "aliased")
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

    private fun sink(ddl: Ddl) = ElasticsearchEventSinks.of(ElasticsearchFixture.restClient, ddl = ddl)
        .sinkOf(type.context, type.name)

    private suspend fun exists(name: String): Boolean =
        ElasticsearchFixture.client.indices().exists { it.index(name) }.await().value()

    @Test
    fun `under none a write to a missing index should be refused, naming it, and create nothing`(): Unit = runBlocking {
        assertThrownBy<IllegalStateException> { runBlocking { sink(Ddl.NONE).write(listOf(record(1))) } }
            .hasMessage(ElasticsearchIndices.missingAlias(index))
        exists(index).assert().isFalse()
        exists(ElasticsearchIndices.concreteOf(index)).assert().isFalse()
    }

    @Test
    fun `under validate a missing index should refuse the write before anything is sent`(): Unit = runBlocking {
        assertThrownBy<IllegalStateException> { runBlocking { sink(Ddl.VALIDATE).write(listOf(record(1))) } }
            .hasMessage(ElasticsearchIndices.missingAlias(index))
        exists(index).assert().isFalse()
    }

    @Test
    fun `under auto the pair should be created, and made again after its index is deleted under the sink`(): Unit =
        runBlocking {
            val sink = sink(Ddl.AUTO)
            sink.write(listOf(record(1)))
            ElasticsearchFixture.client.indices().existsAlias { it.name(index) }.await().value().assert().isTrue()
            exists(ElasticsearchIndices.concreteOf(index)).assert().isTrue()
            ElasticsearchFixture.client.indices().delete { it.index(ElasticsearchIndices.concreteOf(index)) }.await()
            // The sink made sure of the index already: its next write is refused, not taken into an index of a guessed
            // mapping, and the one after creates the pair again.
            assertThrownBy<IllegalStateException> { runBlocking { sink.write(listOf(record(2))) } }
                .hasMessage(ElasticsearchIndices.missingAlias(index))
            sink.write(listOf(record(2)))
            val mapping = ElasticsearchFixture.client.indices().getMapping { it.index(index) }.await()
            mapping.mappings().keys.assert().containsExactly(ElasticsearchIndices.concreteOf(index))
            mapping.mappings().values.single().mappings().properties()["events"]?.isNested.assert().isTrue()
        }
}
