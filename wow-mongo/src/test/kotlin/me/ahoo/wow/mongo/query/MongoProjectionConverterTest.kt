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

import com.mongodb.client.model.Projections
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.event.EventStreamFieldConverter
import me.ahoo.wow.mongo.query.snapshot.SnapshotFieldConverter
import me.ahoo.wow.serialization.MessageRecords
import org.bson.conversions.Bson
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MongoProjectionConverterTest {

    private val snapshotProjectionConverter = MongoProjectionConverter(SnapshotFieldConverter)
    private val eventStreamProjectionConverter = MongoProjectionConverter(EventStreamFieldConverter)

    @ParameterizedTest
    @MethodSource("toSnapshotMongoProjectionParameters")
    fun toSnapshotMongoProjection(projection: Projection, expected: Bson?) {
        val actual = snapshotProjectionConverter.convert(projection)
        actual.assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("toEventStreamMongoProjectionParameters")
    fun toEventStreamMongoProjection(projection: Projection, expected: Bson?) {
        val actual = eventStreamProjectionConverter.convert(projection)
        actual.assert().isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun toSnapshotMongoProjectionParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Projection.ALL, null),
                Arguments.of(
                    Projection(include = listOf(MessageRecords.AGGREGATE_ID)),
                    Projections.include(listOf(Documents.ID_FIELD))
                ),
                Arguments.of(
                    Projection(exclude = listOf(MessageRecords.AGGREGATE_ID)),
                    Projections.exclude(listOf(Documents.ID_FIELD))
                ),
                Arguments.of(
                    Projection(
                        include = listOf(MessageRecords.AGGREGATE_ID),
                        exclude = listOf(MessageRecords.AGGREGATE_ID)
                    ),
                    Projections.fields(
                        Projections.include(listOf(Documents.ID_FIELD)),
                        Projections.exclude(listOf(Documents.ID_FIELD))
                    )
                ),
            )
        }

        @JvmStatic
        fun toEventStreamMongoProjectionParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Projection.ALL, null),
                Arguments.of(
                    Projection(include = listOf(MessageRecords.ID)),
                    Projections.include(listOf(Documents.ID_FIELD))
                ),
                Arguments.of(
                    Projection(exclude = listOf(MessageRecords.ID)),
                    Projections.exclude(listOf(Documents.ID_FIELD))
                ),
                Arguments.of(
                    Projection(include = listOf(MessageRecords.ID), exclude = listOf(MessageRecords.ID)),
                    Projections.fields(
                        Projections.include(listOf(Documents.ID_FIELD)),
                        Projections.exclude(listOf(Documents.ID_FIELD))
                    )
                ),
            )
        }
    }
}
