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

package me.ahoo.wow.mongo.query.event

import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test

class EventStreamConditionConverterTest {
    @Test
    fun id() {
        val condition = condition { MessageRecords.ID.eq("test") }
        val actual = EventStreamConditionConverter.convert(condition)
        val expected = Filters.eq(Documents.ID_FIELD, condition.valueAs<String>())
        actual.assert().isEqualTo(expected)
    }

    @Test
    fun aggregateId() {
        val condition = condition { aggregateId("aggregateId") }
        val actual = EventStreamConditionConverter.convert(condition)
        val expected = Filters.eq(MessageRecords.AGGREGATE_ID, condition.valueAs<String>())
        actual.assert().isEqualTo(expected)
    }

    @Test
    fun aggregateIds() {
        val condition = condition { aggregateIds("aggregateIds") }
        val actual = EventStreamConditionConverter.convert(condition)
        val expected = Filters.`in`(MessageRecords.AGGREGATE_ID, condition.valueAs<Iterable<String>>())
        actual.assert().isEqualTo(expected)
    }
}
