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

package me.ahoo.wow.mongo.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.stream.Stream

class MongoQueryResultDecoderTest {
    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("malformedScalarCases")
    fun `dynamic result rejects values incompatible with the schema kind`(
        kind: QueryFieldValueKind,
        value: Any
    ) {
        val error = assertThrows<QueryException> {
            decodeDynamic(QueryFieldSchema(VALUE, kind, nullable = false), value)
        }

        assertResultInvalid(error)
    }

    @Test
    fun `typed result uses the same schema value validation as dynamic result`() {
        val field = QueryFieldSchema(VALUE, QueryFieldValueKind.STRING, nullable = false)
        val decoder = decoder(field)

        val error = assertThrows<QueryException> {
            decoder.decode<AnyValueResult>(
                Document("value", 42),
                QueryPlanResultShape.Typed(AnyValueResult::class.java, setOf(VALUE)),
                mapOf(VALUE to "value")
            )
        }

        assertResultInvalid(error)
    }

    @Test
    fun `scalar collection validates each element against the schema kind`() {
        val field = QueryFieldSchema(
            VALUE,
            QueryFieldValueKind.INTEGER,
            nullable = false,
            collectionKind = QueryCollectionKind.SCALAR
        )

        val error = assertThrows<QueryException> { decodeDynamic(field, listOf(1, "two")) }

        assertResultInvalid(error)
    }

    @ParameterizedTest(name = "DECIMAL rejects non-finite {0}")
    @MethodSource("nonFiniteDecimalCases")
    fun `decimal rejects non-finite values for dynamic and typed results`(value: Any) {
        val field = QueryFieldSchema(VALUE, QueryFieldValueKind.DECIMAL, nullable = false)

        assertResultInvalid(assertThrows { decodeDynamic(field, value) })
        assertResultInvalid(
            assertThrows {
                decoder(field).decode<AnyValueResult>(
                    Document("value", value),
                    QueryPlanResultShape.Typed(AnyValueResult::class.java, setOf(VALUE)),
                    mapOf(VALUE to "value")
                )
            }
        )
    }

    @ParameterizedTest(name = "DECIMAL accepts finite {0}")
    @MethodSource("finiteDecimalCases")
    fun `decimal accepts finite canonical numeric representations`(value: Any, expected: Any) {
        decodeDynamic(
            QueryFieldSchema(VALUE, QueryFieldValueKind.DECIMAL, nullable = false),
            value
        ).assert().isEqualTo(expected)
    }

    @Test
    fun `system time rejects an integer outside the epoch millis range`() {
        val systemFields = QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT)
        val field = systemFields.single { it.path.value == "eventTime" }
        val decoder = MongoQueryResultDecoder(MongoQueryFieldBinding.bind(QuerySchema(TARGET, systemFields)))
        val outsideLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)

        val error = assertThrows<QueryException> {
            decoder.decode<DynamicDocument>(
                Document(field.path.value, outsideLong),
                QueryPlanResultShape.Dynamic(setOf(field.path)),
                mapOf(field.path to field.path.value)
            )
        }

        assertResultInvalid(error)
    }

    @Test
    fun `valid BSON scalar representations normalize without aliasing binary arrays`() {
        val bytes = byteArrayOf(1, 2, 3)
        val fields = validScalarFields()
        val schema = QuerySchema(TARGET, fields.values)
        val projection = fields.keys.associateWith(LogicalField::value)
        val result = MongoQueryResultDecoder(MongoQueryFieldBinding.bind(schema)).decode<DynamicDocument>(
            validScalarDocument(bytes),
            QueryPlanResultShape.Dynamic(fields.keys),
            projection
        )

        result["boolean"].assert().isEqualTo(true)
        result["integer"].assert().isEqualTo(7L)
        result["decimal"].assert().isEqualTo(BigDecimal("8.25"))
        result["time"].assert().isEqualTo(Instant.parse("2026-08-13T00:00:00Z"))
        result["enum"].assert().isEqualTo("READY")
        (result["binary"] as ByteArray).assert().isEqualTo(byteArrayOf(1, 2, 3))
        bytes[0] = 9
        (result["binary"] as ByteArray).assert().isEqualTo(byteArrayOf(1, 2, 3))
        result["object"].assert().isEqualTo(mapOf("name" to "value"))
        result["map"].assert().isEqualTo(mapOf("key" to 1))
    }

    private fun validScalarFields(): Map<LogicalField, QueryFieldSchema> = linkedMapOf(
        scalarField("boolean", QueryFieldValueKind.BOOLEAN),
        scalarField("integer", QueryFieldValueKind.INTEGER),
        scalarField("decimal", QueryFieldValueKind.DECIMAL),
        scalarField("time", QueryFieldValueKind.TIME),
        scalarField("enum", QueryFieldValueKind.ENUM),
        scalarField("binary", QueryFieldValueKind.BINARY),
        scalarField("object", QueryFieldValueKind.OBJECT),
        scalarField("map", QueryFieldValueKind.MAP)
    )

    private fun scalarField(name: String, kind: QueryFieldValueKind): Pair<LogicalField, QueryFieldSchema> {
        val field = LogicalField(name)
        return field to QueryFieldSchema(field, kind, nullable = false)
    }

    private fun validScalarDocument(bytes: ByteArray): Document = Document(
        mapOf(
            "boolean" to true,
            "integer" to 7L,
            "decimal" to Decimal128(BigDecimal("8.25")),
            "time" to "2026-08-13T00:00:00Z",
            "enum" to "READY",
            "binary" to bytes,
            "object" to Document("name", "value"),
            "map" to Document("key", 1)
        )
    )

    @Test
    fun `binary driver wrappers are normalized to defensive byte arrays`() {
        val field = QueryFieldSchema(VALUE, QueryFieldValueKind.BINARY, nullable = false)

        val binary = decodeDynamic(field, Binary(byteArrayOf(4, 5))) as ByteArray

        binary.assert().isEqualTo(byteArrayOf(4, 5))
    }

    private fun decodeDynamic(field: QueryFieldSchema, value: Any): Any? = decoder(field)
        .decode<DynamicDocument>(
            Document("value", value),
            QueryPlanResultShape.Dynamic(setOf(VALUE)),
            mapOf(VALUE to "value")
        )[VALUE.value]

    private fun decoder(field: QueryFieldSchema): MongoQueryResultDecoder = MongoQueryResultDecoder(
        MongoQueryFieldBinding.bind(QuerySchema(TARGET, listOf(field)))
    )

    private fun assertResultInvalid(error: QueryException) {
        error.code.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
        error.stage.assert().isEqualTo(QueryStage.EXECUTION)
        error.reason.assert().isEqualTo(QueryErrorReason.RESULT_INVALID)
        error.message.assert().isEqualTo("RESULT_VALIDATION_FAILED:EXECUTION:RESULT_INVALID")
        error.cause.assert().isNull()
    }

    data class AnyValueResult(val value: Any)

    private companion object {
        val TARGET = QueryTarget(MaterializedNamedAggregate("mongo-query-decoder", "value"), QueryDocumentKind.SNAPSHOT)
        val VALUE = LogicalField("value")

        @JvmStatic
        fun malformedScalarCases(): Stream<Arguments> = Stream.of(
            Arguments.of(QueryFieldValueKind.BOOLEAN, "true"),
            Arguments.of(QueryFieldValueKind.INTEGER, BigDecimal.ONE),
            Arguments.of(QueryFieldValueKind.DECIMAL, "1.5"),
            Arguments.of(QueryFieldValueKind.STRING, 1),
            Arguments.of(QueryFieldValueKind.TIME, 1L),
            Arguments.of(QueryFieldValueKind.ENUM, 1),
            Arguments.of(QueryFieldValueKind.BINARY, "bytes"),
            Arguments.of(QueryFieldValueKind.OBJECT, "object"),
            Arguments.of(QueryFieldValueKind.MAP, "map")
        )

        @JvmStatic
        fun nonFiniteDecimalCases(): Stream<Any> = Stream.of(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Decimal128.NaN,
            Decimal128.POSITIVE_INFINITY,
            Decimal128.NEGATIVE_INFINITY
        )

        @JvmStatic
        fun finiteDecimalCases(): Stream<Arguments> = Stream.of(
            Arguments.of(BigDecimal("1.25"), BigDecimal("1.25")),
            Arguments.of(Decimal128(BigDecimal("2.50")), BigDecimal("2.50")),
            Arguments.of(3L, 3L),
            Arguments.of(4, 4),
            Arguments.of(5.5, 5.5),
            Arguments.of(6.5f, 6.5f)
        )
    }
}
