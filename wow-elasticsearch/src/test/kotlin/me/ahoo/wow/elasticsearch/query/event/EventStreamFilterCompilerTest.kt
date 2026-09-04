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

package me.ahoo.wow.elasticsearch.query.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test

class EventStreamFilterCompilerTest {
    @Test
    fun `match all filter should include deleted event streams`() {
        EventStreamFilterCompiler.compilePhysical(MatchAllFilter)._kind().assert().isEqualTo(
            co.elastic.clients.elasticsearch._types.query_dsl.Query.Kind.MatchAll,
        )
    }

    @Test
    fun `event metadata filters should use source metadata fields`() {
        EventStreamFilterCompiler.compilePhysical(IdFilter("id-1")).term().field().assert()
            .isEqualTo(MessageRecords.ID)
        EventStreamFilterCompiler.compilePhysical(AggregateIdFilter("aggregate-1")).term().field().assert()
            .isEqualTo(MessageRecords.AGGREGATE_ID)

        EventStreamFilterCompiler.compilePhysical(IdsFilter(listOf("id-1", "id-2"))).terms().apply {
            field().assert().isEqualTo(MessageRecords.ID)
            terms().value().map { it.stringValue() }.assert().containsExactly("id-1", "id-2")
        }
        EventStreamFilterCompiler.compilePhysical(
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
        ).terms().apply {
            field().assert().isEqualTo(MessageRecords.AGGREGATE_ID)
            terms().value().map { it.stringValue() }.assert().containsExactly("aggregate-1", "aggregate-2")
        }
    }

    @Test
    fun `generic document id predicates should use event id field`() {
        val actual = EventStreamFilterCompiler.compilePhysical(filter { "_id" eq "stream-id" })

        actual.term().field().assert().isEqualTo(MessageRecords.ID)
        actual.term().value().stringValue().assert().isEqualTo("stream-id")
    }

    @Test
    fun `should qualify relative element predicate fields`() {
        val actual = EventStreamFilterCompiler.compilePhysical(
            filter {
                "body".elementMatch {
                    "name" eq "value"
                }
            },
        )

        actual.nested().path().assert().isEqualTo("body")
        actual.nested().query().term().field().assert().isEqualTo("body.name")
    }
}
