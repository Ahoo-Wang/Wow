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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.MessageRecords
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MongoSortCompilerTest {
    private val compiler = MongoSortCompiler
    private val snapshotSchema = sortSchema(QueryModel.SNAPSHOT, MessageRecords.AGGREGATE_ID)
    private val eventStreamSchema = sortSchema(QueryModel.EVENT_STREAM, MessageRecords.ID)

    @ParameterizedTest
    @MethodSource("toSnapshotMongoSortParameters")
    fun toSnapshotMongoSort(sort: List<Sort>, expected: Bson?) {
        val actual = compiler.compile(sort, snapshotSchema)
        actual.assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("toEventStreamMongoSortParameters")
    fun toEventStreamMongoSort(sort: List<Sort>, expected: Bson?) {
        val actual = compiler.compile(sort, eventStreamSchema)
        actual.assert().isEqualTo(expected)
    }

    @Test
    fun `unknown sort fields cannot become physical field names`() {
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(listOf(Sort(QueryField("unknown"), Sort.Direction.ASC)), snapshotSchema)
        }
    }

    @Test
    fun `logical sort compiles to its native binding`() {
        val schema = sortSchema(QueryModel.SNAPSHOT, "state.name", "storage.name")
        compiler.compile(listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)), schema).assert()
            .isEqualTo(Sorts.orderBy(Sorts.ascending("storage.name")))
    }

    companion object {
        private fun sortSchema(model: QueryModel, logicalPath: String, physicalPath: String = Documents.ID_FIELD) =
            mongoTestSchema(
                model,
                fields = mapOf(
                    QueryField(logicalPath) to MongoTestField(mongoScalar(), setOf(QueryCapability.SORT), physicalPath)
                )
            )

        @JvmStatic
        fun toSnapshotMongoSortParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(emptyList<Sort>(), null),
                Arguments.of(
                    listOf(Sort(QueryField(MessageRecords.AGGREGATE_ID), Sort.Direction.ASC)),
                    Sorts.orderBy(Sorts.ascending(Documents.ID_FIELD))
                ),
            )
        }

        @JvmStatic
        fun toEventStreamMongoSortParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(emptyList<Sort>(), null),
                Arguments.of(
                    listOf(Sort(QueryField(MessageRecords.ID), Sort.Direction.ASC)),
                    Sorts.orderBy(Sorts.ascending(Documents.ID_FIELD))
                ),
            )
        }
    }
}
