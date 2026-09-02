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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.event.EventStreamFieldConverter
import me.ahoo.wow.mongo.query.snapshot.SnapshotFieldConverter
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.serialization.MessageRecords
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MongoProjectionConverterTest {

    private val snapshotProjectionConverter = MongoProjectionConverter(SnapshotFieldConverter)
    private val eventStreamProjectionConverter = MongoProjectionConverter(EventStreamFieldConverter)
    private val schema = projectionSchema()

    @Test
    fun `should compile included logical nodes to schema projection fields`() {
        val include = listOf(QueryField("state"), QueryField("state.name"))
        val projection = Projection(include = include)

        snapshotProjectionConverter.convert(projection, schema).assert().isEqualTo(
            Projections.include("document", "document.name"),
        )
        projection.include.assert().isSameAs(include)
    }

    @Test
    fun `should compile excluded logical nodes to schema projection fields`() {
        snapshotProjectionConverter.convert(
            Projection(exclude = listOf(QueryField("state"), QueryField("state.name"))),
            schema,
        ).assert().isEqualTo(
            Projections.exclude("document", "document.name"),
        )
    }

    @ParameterizedTest
    @MethodSource("toSnapshotMongoProjectionParameters")
    fun toSnapshotMongoProjection(projection: Projection, expected: Bson?) {
        val actual = snapshotProjectionConverter.convert(projection, null)
        actual.assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("toEventStreamMongoProjectionParameters")
    fun toEventStreamMongoProjection(projection: Projection, expected: Bson?) {
        val actual = eventStreamProjectionConverter.convert(projection, null)
        actual.assert().isEqualTo(expected)
    }

    companion object {
        private fun projectionSchema() = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                QueryField("state") to projectionFieldSchema(QueryField("document")),
                QueryField("state.name") to projectionFieldSchema(QueryField("document.name")),
            ),
        )

        private fun projectionFieldSchema(projectionField: QueryField) = QueryFieldSchema(
            title = null,
            description = null,
            enumValues = null,
            valueTypes = emptySet(),
            nullable = true,
            required = false,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            bindings = emptyMap(),
            projectionField = projectionField,
            rewriteMode = QueryRewriteMode.NONE,
        )

        @JvmStatic
        fun toSnapshotMongoProjectionParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Projection.ALL, null),
                Arguments.of(
                    Projection(include = listOf(QueryField(MessageRecords.AGGREGATE_ID))),
                    Projections.include(listOf(Documents.ID_FIELD))
                ),
                Arguments.of(
                    Projection(exclude = listOf(QueryField(MessageRecords.AGGREGATE_ID))),
                    Projections.exclude(listOf(Documents.ID_FIELD))
                ),
                Arguments.of(
                    Projection(
                        include = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
                        exclude = listOf(QueryField(MessageRecords.AGGREGATE_ID))
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
                    Projection(include = listOf(QueryField(MessageRecords.ID), QueryField("field1"))),
                    Projections.include(listOf(Documents.ID_FIELD, "field1"))
                ),
                Arguments.of(
                    Projection(exclude = listOf(QueryField(MessageRecords.ID))),
                    Projections.exclude(listOf(Documents.ID_FIELD))
                ),
                Arguments.of(
                    Projection(
                        include = listOf(QueryField(MessageRecords.ID)),
                        exclude = listOf(QueryField(MessageRecords.ID))
                    ),
                    Projections.fields(
                        Projections.include(listOf(Documents.ID_FIELD)),
                        Projections.exclude(listOf(Documents.ID_FIELD))
                    )
                ),
            )
        }
    }
}
