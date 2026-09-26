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

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.query.compile
import me.ahoo.wow.elasticsearch.query.nativeBindings
import me.ahoo.wow.elasticsearch.query.nativeSchema
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test

class EventStreamFilterCompilerTest {
    private val schema = nativeSchema(
        model = QueryModel.EVENT_STREAM,
        fields = buildMap {
            listOf(MessageRecords.ID, MessageRecords.AGGREGATE_ID, "_id", "${MessageRecords.BODY}.name").forEach {
                put(QueryField(it), nativeBindings(QueryField(it), QueryCapability.EXACT_MATCH))
            }
            put(
                QueryField(MessageRecords.BODY),
                nativeBindings(QueryField(MessageRecords.BODY), QueryCapability.ELEMENT_SCOPE),
            )
        },
    )

    private fun EventStreamFilterCompiler.compileAdmitted(filter: FilterExpression): Query = compile(filter, schema)

    @Test
    fun `match all filter should include deleted event streams`() {
        EventStreamFilterCompiler.compileAdmitted(MatchAllFilter)._kind().assert().isEqualTo(
            Query.Kind.MatchAll,
        )
    }

    @Test
    fun `event metadata filters should use source metadata fields`() {
        EventStreamFilterCompiler.compileAdmitted(IdFilter("id-1")).term().field().assert()
            .isEqualTo(MessageRecords.ID)
        EventStreamFilterCompiler.compileAdmitted(AggregateIdFilter("aggregate-1")).term().field().assert()
            .isEqualTo(MessageRecords.AGGREGATE_ID)

        EventStreamFilterCompiler.compileAdmitted(IdsFilter(listOf("id-1", "id-2"))).terms().apply {
            field().assert().isEqualTo(MessageRecords.ID)
            terms().value().map { it.stringValue() }.assert().containsExactly("id-1", "id-2")
        }
        EventStreamFilterCompiler.compileAdmitted(
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
        ).terms().apply {
            field().assert().isEqualTo(MessageRecords.AGGREGATE_ID)
            terms().value().map { it.stringValue() }.assert().containsExactly("aggregate-1", "aggregate-2")
        }
    }

    @Test
    fun `a field bound to the native document id compiles to an ids query`() {
        val actual = EventStreamFilterCompiler.compileAdmitted(filter { "_id" eq "stream-id" })

        actual.ids().values().assert().containsExactly("stream-id")
    }

    @Test
    fun `should qualify relative element predicate fields`() {
        val actual = EventStreamFilterCompiler.compileAdmitted(
            filter {
                MessageRecords.BODY.elementMatch {
                    "name" eq "value"
                }
            },
        )

        actual.nested().path().assert().isEqualTo("body")
        actual.nested().query().term().field().assert().isEqualTo("body.name")
    }
}
