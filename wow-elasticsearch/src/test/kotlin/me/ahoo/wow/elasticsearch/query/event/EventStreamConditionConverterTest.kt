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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test

class EventStreamConditionConverterTest {
    @Test
    fun `should convert event stream id condition`() {
        val actual = EventStreamConditionConverter.convert(condition { id("streamId") })

        actual.term().field().assert().isEqualTo(MessageRecords.ID)
        actual.term().value().stringValue().assert().isEqualTo("streamId")
    }

    @Test
    fun `should convert event stream ids condition`() {
        val actual = EventStreamConditionConverter.convert(condition { ids("streamId-1", "streamId-2") })

        actual.terms().field().assert().isEqualTo(MessageRecords.ID)
        actual.terms().terms().value().map { it.stringValue() }
            .assert().containsExactly("streamId-1", "streamId-2")
    }

    @Test
    fun `should convert aggregate id condition`() {
        val condition = condition { aggregateId("aggregateId") }
        val actual = EventStreamConditionConverter.convert(condition)
        val expected = term {
            it.field(MessageRecords.AGGREGATE_ID)
                .value(FieldValue.of(condition.value))
        }
        actual.term().field().assert().isEqualTo(expected.term().field())
    }

    @Test
    fun `should convert aggregate ids condition`() {
        val condition = condition { aggregateIds("aggregateIds") }
        val actual = EventStreamConditionConverter.convert(condition)
        val expected = terms {
            it.field(MessageRecords.AGGREGATE_ID)
                .terms { builder ->
                    condition.valueAs<List<Any>>().map {
                        FieldValue.of(it)
                    }.toList().let { builder.value(it) }
                }
        }
        actual.terms().field().assert().isEqualTo(expected.terms().field())
    }
}
