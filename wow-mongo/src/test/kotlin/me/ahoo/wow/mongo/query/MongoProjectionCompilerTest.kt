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
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.serialization.MessageRecords
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MongoProjectionCompilerTest {

    private val compiler = MongoProjectionCompiler
    private val snapshotSchema = projectionSchema(QueryModel.SNAPSHOT, MessageRecords.AGGREGATE_ID)
    private val eventStreamSchema = projectionSchema(QueryModel.EVENT_STREAM, MessageRecords.ID)

    @Test
    fun `should remove redundant included descendants after physical mapping`() {
        val include = listOf(QueryField("state"), QueryField("state.name"))
        val projection = Projection(include = include)

        compiler.compile(projection, snapshotSchema).assert().isEqualTo(
            Projections.include("document"),
        )
        projection.include.assert().isSameAs(include)
    }

    @Test
    fun `should remove redundant excluded descendants after physical mapping`() {
        compiler.compile(
            Projection(exclude = listOf(QueryField("state"), QueryField("state.name"))),
            snapshotSchema,
        ).assert().isEqualTo(
            Projections.exclude("document"),
        )
    }

    @Test
    fun `should reject mixed inclusion and exclusion`() {
        assertThrows<IllegalArgumentException> {
            compiler.compile(
                Projection(
                    include = listOf(QueryField("state.name")),
                    exclude = listOf(QueryField("other")),
                ),
                snapshotSchema,
            )
        }
    }

    @Test
    fun `cursor projection should reject mixed projection before restoring an excluded sort field`() {
        assertThrows<IllegalArgumentException> {
            compiler.cursorProjection(
                Projection(
                    include = listOf(QueryField("state.name")),
                    exclude = listOf(QueryField("other")),
                ),
                sortFields = listOf("other"),
                snapshotSchema,
            )
        }
    }

    @Test
    fun `should reject excluding id when id is explicitly included`() {
        assertThrows<IllegalArgumentException> {
            compiler.compile(
                Projection(
                    include = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
                    exclude = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
                ),
                snapshotSchema,
            )
        }
    }

    @Test
    fun `should allow explicitly including id with an exclusion projection`() {
        compiler.compile(
            Projection(
                include = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
                exclude = listOf(QueryField("state.name")),
            ),
            snapshotSchema,
        ).assert().isEqualTo(
            Projections.exclude("document.name"),
        )
    }

    @Test
    fun `cursor projection should retain exclusion semantics when id is explicitly included`() {
        val projection = compiler.cursorProjection(
            Projection(
                include = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
                exclude = listOf(QueryField("state.name")),
            ),
            sortFields = listOf("rank"),
            snapshotSchema,
        )

        compiler.compile(projection).assert().isEqualTo(Projections.exclude("document.name"))
    }

    @Test
    fun `should reject mixed projections that select an id descendant`() {
        listOf(
            Projection(
                include = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
                exclude = listOf(QueryField("_id.child")),
            ),
            Projection(
                include = listOf(QueryField("_id.child")),
                exclude = listOf(QueryField(MessageRecords.AGGREGATE_ID)),
            ),
        ).forEach { projection ->
            assertThrows<IllegalArgumentException> {
                compiler.compile(projection, snapshotSchema)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("toSnapshotMongoProjectionParameters")
    fun toSnapshotMongoProjection(projection: Projection, expected: Bson?) {
        val actual = compiler.compile(projection, snapshotSchema)
        actual.assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("toEventStreamMongoProjectionParameters")
    fun toEventStreamMongoProjection(projection: Projection, expected: Bson?) {
        val actual = compiler.compile(projection, eventStreamSchema)
        actual.assert().isEqualTo(expected)
    }

    companion object {
        private fun projectionSchema(model: QueryModel, idField: String) = mongoTestSchema(
            model = model,
            capabilities = emptySet(),
            fields = mapOf(
                projectionFieldSchema("state", "document"),
                projectionFieldSchema("other", "other"),
                projectionFieldSchema("field1", "field1"),
                projectionFieldSchema("_id.child", "_id.child"),
                projectionFieldSchema("state.name", "document.name"),
                projectionFieldSchema(idField, Documents.ID_FIELD),
            ),
        )

        private fun projectionFieldSchema(logicalPath: String, physicalPath: String): Pair<QueryField, MongoTestField> =
            QueryField(logicalPath) to MongoTestField(
                if (logicalPath == "state") QueryValueSchema(QueryValueKind.OBJECT) else mongoScalar(),
                setOf(QueryCapability.PRESENCE), physicalPath,
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
                        include = listOf(QueryField("state.name")),
                        exclude = listOf(QueryField(MessageRecords.AGGREGATE_ID))
                    ),
                    Projections.fields(
                        Projections.include(listOf("document.name")),
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
                        include = listOf(QueryField("state.name")),
                        exclude = listOf(QueryField(MessageRecords.ID))
                    ),
                    Projections.fields(
                        Projections.include(listOf("document.name")),
                        Projections.exclude(listOf(Documents.ID_FIELD))
                    )
                ),
            )
        }
    }
}
