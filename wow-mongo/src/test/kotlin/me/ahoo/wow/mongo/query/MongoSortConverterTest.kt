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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Sorts
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.event.EventStreamFieldConverter
import me.ahoo.wow.mongo.query.snapshot.SnapshotFieldConverter
import me.ahoo.wow.serialization.MessageRecords
import org.bson.conversions.Bson
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MongoSortConverterTest {
    private val snapshotSortConverter = MongoSortConverter(SnapshotFieldConverter)
    private val eventStreamSortConverter = MongoSortConverter(EventStreamFieldConverter)

    @ParameterizedTest
    @MethodSource("toSnapshotMongoSortParameters")
    fun toSnapshotMongoSort(sort: List<Sort>, expected: Bson?) {
        val actual = snapshotSortConverter.convert(sort)
        actual.assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("toEventStreamMongoSortParameters")
    fun toEventStreamMongoSort(sort: List<Sort>, expected: Bson?) {
        val actual = eventStreamSortConverter.convert(sort)
        actual.assert().isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun toSnapshotMongoSortParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(emptyList<Sort>(), null),
                Arguments.of(
                    listOf(Sort(MessageRecords.AGGREGATE_ID, Sort.Direction.ASC)),
                    Sorts.orderBy(Sorts.ascending(Documents.ID_FIELD))
                ),
                Arguments.of(
                    listOf(
                        Sort(MessageRecords.AGGREGATE_ID, Sort.Direction.ASC),
                        Sort(MessageRecords.AGGREGATE_ID, Sort.Direction.DESC)
                    ),
                    Sorts.orderBy(Sorts.ascending(Documents.ID_FIELD), Sorts.descending(Documents.ID_FIELD))
                ),
            )
        }

        @JvmStatic
        fun toEventStreamMongoSortParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(emptyList<Sort>(), null),
                Arguments.of(
                    listOf(Sort(MessageRecords.ID, Sort.Direction.ASC)),
                    Sorts.orderBy(Sorts.ascending(Documents.ID_FIELD))
                ),
                Arguments.of(
                    listOf(
                        Sort(MessageRecords.ID, Sort.Direction.ASC),
                        Sort(MessageRecords.ID, Sort.Direction.DESC)
                    ),
                    Sorts.orderBy(Sorts.ascending(Documents.ID_FIELD), Sorts.descending(Documents.ID_FIELD))
                ),
            )
        }
    }
}
