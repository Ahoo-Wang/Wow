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

package me.ahoo.wow.elasticsearch.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ImmutableDynamicDocument
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
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.PortableQueryResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.time.Instant
import java.util.Base64

class ElasticsearchQueryResultDecoderTest {
    private val binding = ElasticsearchQueryFieldBinding.bind(
        PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
    )
    private val decoder = ElasticsearchQueryResultDecoder(binding)

    @Test
    fun `typed DynamicDocument materializes the exact validated projection`() {
        val profile = LogicalField("profile")
        val city = LogicalField("profile.city")
        val fields = listOf(
            QueryFieldSchema(profile, QueryFieldValueKind.OBJECT, nullable = false),
            QueryFieldSchema(city, QueryFieldValueKind.STRING, nullable = false),
            QueryFieldSchema(OTHER, QueryFieldValueKind.STRING, nullable = false),
        )
        val result = decoder(fields).decode<DynamicDocument>(
            mapOf("profile" to mapOf("city" to "杭州"), "other" to "not-projected"),
            QueryPlanResultShape.Typed(DynamicDocument::class.java, setOf(city)),
            mapOf(city to "profile.city"),
        )

        result.assert().isEqualTo(mapOf("profile" to mapOf("city" to "杭州")))
    }

    @Test
    fun `typed DynamicDocument preserves a nullable projected field`() {
        val field = QueryFieldSchema(VALUE, QueryFieldValueKind.STRING, nullable = true)

        val result = decoder(listOf(field)).decode<DynamicDocument>(
            emptyMap(),
            QueryPlanResultShape.Typed(DynamicDocument::class.java, setOf(VALUE)),
            mapOf(VALUE to "value"),
        )

        result.containsKey(VALUE.value).assert().isTrue()
        result[VALUE.value].assert().isNull()
    }

    @Test
    fun `internal legacy typed marker uses structured dynamic decoding`() {
        val field = QueryFieldSchema(VALUE, QueryFieldValueKind.STRING, nullable = false)

        val result = decoder(listOf(field)).decode<DynamicDocument>(
            mapOf("value" to "visible"),
            QueryPlanResultShape.Typed(Class.forName(LEGACY_TYPED_MARKER), setOf(VALUE)),
            mapOf(VALUE to "value"),
        )

        result[VALUE.value].assert().isEqualTo("visible")
    }

    @Test
    fun `direct typed immutable DynamicDocument keeps typed conversion semantics`() {
        val field = QueryFieldSchema(VALUE, QueryFieldValueKind.STRING, nullable = false)

        val error = assertThrows<QueryException> {
            decoder(listOf(field)).decode<ImmutableDynamicDocument>(
                mapOf("value" to "visible"),
                QueryPlanResultShape.Typed(ImmutableDynamicDocument::class.java, setOf(VALUE)),
                mapOf(VALUE to "value"),
            )
        }

        assertResultInvalid(error)
    }

    @Test
    fun `typed DynamicDocument rejects malformed projected values with the stable tuple`() {
        val field = QueryFieldSchema(VALUE, QueryFieldValueKind.STRING, nullable = false)

        val error = assertThrows<QueryException> {
            decoder(listOf(field)).decode<DynamicDocument>(
                mapOf("value" to 42),
                QueryPlanResultShape.Typed(DynamicDocument::class.java, setOf(VALUE)),
                mapOf(VALUE to "value"),
            )
        }

        assertResultInvalid(error)
    }

    @Test
    fun `dynamic projection is validated and strips recursive presence metadata`() {
        val shape = QueryPlanResultShape.Dynamic(
            setOf(PortableQueryDataset.LOGICAL_ID, PortableQueryDataset.PROFILE),
        )
        val projection = binding.projection(shape.fields)

        val result = decoder.decode<ImmutableDynamicDocument>(
            mapOf(
                "logicalId" to "d01",
                "profile" to mapOf(
                    "city" to "杭州",
                    "__wow_query" to mapOf("present" to listOf("city")),
                ),
                "__wow_query" to mapOf("present" to listOf("logicalId", "profile")),
            ),
            shape,
            projection,
        )

        result["logicalId"].assert().isEqualTo("d01")
        result["profile"].assert().isEqualTo(mapOf("city" to "杭州"))
    }

    @Test
    fun `typed projection is reconstructed without retaining backend source`() {
        val shape = QueryPlanResultShape.Typed(
            PortableQueryResult::class.java,
            setOf(PortableQueryDataset.LOGICAL_ID),
        )

        decoder.decode<PortableQueryResult>(
            mapOf("logicalId" to "d01", "title" to "not projected"),
            shape,
            binding.projection(shape.fields),
        ).assert().isEqualTo(PortableQueryResult("d01"))
    }

    @Test
    fun `missing non-null field and wrong scalar type fail closed`() {
        val logicalId = QueryPlanResultShape.Dynamic(setOf(PortableQueryDataset.LOGICAL_ID))
        assertThrows<QueryException> {
            decoder.decode<ImmutableDynamicDocument>(emptyMap(), logicalId, binding.projection(logicalId.fields))
        }

        val rank = QueryPlanResultShape.Dynamic(setOf(PortableQueryDataset.RANK))
        assertThrows<QueryException> {
            decoder.decode<ImmutableDynamicDocument>(
                mapOf("rank" to "1"),
                rank,
                binding.projection(rank.fields),
            )
        }
    }

    @Test
    fun `scalar and collection shapes are enforced`() {
        val scalar = QueryFieldSchema(VALUE, QueryFieldValueKind.STRING, nullable = false)
        assertResultInvalid(assertThrows { decodeDynamic(listOf(scalar), mapOf("value" to listOf("a")), VALUE) })

        val collection = QueryFieldSchema(
            VALUE,
            QueryFieldValueKind.INTEGER,
            nullable = false,
            collectionKind = QueryCollectionKind.SCALAR,
        )
        assertResultInvalid(assertThrows { decodeDynamic(listOf(collection), mapOf("value" to 1L), VALUE) })
        assertResultInvalid(
            assertThrows { decodeDynamic(listOf(collection), mapOf("value" to listOf(1L, null)), VALUE) },
        )
    }

    @Test
    fun `nullable object ancestor materializes as null but malformed ancestor fails`() {
        val profile = LogicalField("profile")
        val city = LogicalField("profile.city")
        val fields = listOf(
            QueryFieldSchema(profile, QueryFieldValueKind.OBJECT, nullable = true),
            QueryFieldSchema(city, QueryFieldValueKind.STRING, nullable = false),
        )
        val decoder = decoder(fields)
        val shape = QueryPlanResultShape.Typed(NullableProfileResult::class.java, setOf(city))

        listOf(emptyMap(), mapOf("profile" to null)).forEach { source ->
            decodeDynamic(fields, source, city).assert().isNull()
            decoder.decode<NullableProfileResult>(source, shape, linkedMapOf(city to "profile.city"))
                .profile.assert().isNull()
        }
        assertResultInvalid(assertThrows { decodeDynamic(fields, mapOf("profile" to "invalid"), city) })
    }

    @Test
    fun `sparse typed schema reconstructs undeclared object ancestors and keeps failures low information`() {
        val city = LogicalField("profile.address.city")
        val fields = listOf(QueryFieldSchema(city, QueryFieldValueKind.STRING, nullable = true))
        val decoder = decoder(fields)
        val dynamic = QueryPlanResultShape.Dynamic(setOf(city))
        val typed = QueryPlanResultShape.Typed(SparseCityResult::class.java, setOf(city))
        val projection = linkedMapOf(city to city.value)
        val cases = listOf(
            mapOf<String, Any?>("profile" to mapOf("address" to mapOf("city" to "杭州"))) to "杭州",
            emptyMap<String, Any?>() to null,
            mapOf<String, Any?>("profile" to mapOf("address" to mapOf("city" to null))) to null,
        )

        cases.forEach { (source, expected) ->
            decoder.decode<ImmutableDynamicDocument>(source, dynamic, projection)[city.value]
                .assert().isEqualTo(expected)
            decoder.decode<SparseCityResult>(source, typed, projection).assert().isEqualTo(
                SparseCityResult(SparseProfile(SparseAddress(expected))),
            )
        }
        listOf(dynamic, typed).forEach { shape ->
            val error = assertThrows<QueryException> {
                decoder.decode<Any>(mapOf("profile" to "invalid"), shape, projection)
            }
            assertResultInvalid(error)
        }
    }

    @Test
    fun `typed object collection merges same-index children independent of projection order`() {
        val items = LogicalField("items")
        val sku = LogicalField("items.sku")
        val quantity = LogicalField("items.quantity")
        val fields = listOf(
            QueryFieldSchema(
                items,
                QueryFieldValueKind.OBJECT,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT,
                nested = false,
            ),
            QueryFieldSchema(sku, QueryFieldValueKind.STRING, nullable = false),
            QueryFieldSchema(quantity, QueryFieldValueKind.INTEGER, nullable = false),
        )
        val decoder = decoder(fields)
        val source = mapOf(
            "items" to listOf(
                mapOf("sku" to "A", "quantity" to 1L),
                mapOf("sku" to "B", "quantity" to 2L),
            ),
        )
        val shape = QueryPlanResultShape.Typed(ItemsResult::class.java, setOf(sku, quantity))

        decoder.decode<ImmutableDynamicDocument>(
            source,
            QueryPlanResultShape.Dynamic(setOf(items)),
            linkedMapOf(items to "items"),
        )["items"].assert().isEqualTo(source["items"])

        listOf(
            linkedMapOf(sku to "items.sku", quantity to "items.quantity"),
            linkedMapOf(quantity to "items.quantity", sku to "items.sku"),
        ).forEach { projection ->
            decoder.decode<ItemsResult>(source, shape, projection).assert().isEqualTo(
                ItemsResult(listOf(ItemResult("A", 1), ItemResult("B", 2))),
            )
        }
    }

    @Test
    fun `typed object collection rejects child array length collision`() {
        val items = LogicalField("items")
        val sku = LogicalField("items.sku")
        val quantity = LogicalField("items.quantity")
        val fields = listOf(
            QueryFieldSchema(
                items,
                QueryFieldValueKind.OBJECT,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT,
            ),
            QueryFieldSchema(sku, QueryFieldValueKind.STRING, nullable = false),
            QueryFieldSchema(quantity, QueryFieldValueKind.INTEGER, nullable = false),
        )
        val shape = QueryPlanResultShape.Typed(ItemsResult::class.java, setOf(sku, quantity))

        val error = assertThrows<QueryException> {
            decoder(fields).decode<ItemsResult>(
                mapOf("skus" to listOf("A", "B"), "quantities" to listOf(1L)),
                shape,
                linkedMapOf(sku to "skus", quantity to "quantities"),
            )
        }

        assertResultInvalid(error)
    }

    @Test
    fun `wire values normalize defensively and enforce time representation`() {
        val binary = LogicalField("binary")
        val systemTime = QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT).single { it.path.value == "eventTime" }
        val applicationTime = QueryFieldSchema(
            LogicalField("applicationTime"),
            QueryFieldValueKind.TIME,
            nullable = false,
        )
        val fields = QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT) +
            QueryFieldSchema(binary, QueryFieldValueKind.BINARY, nullable = false) + applicationTime
        val bytes = byteArrayOf(1, 2, 3)
        val encoded = Base64.getEncoder().encodeToString(bytes)

        val decoded = decodeDynamic(fields, mapOf("binary" to encoded), binary) as ByteArray
        decoded.assert().isEqualTo(bytes)
        bytes[0] = 9
        decoded.assert().isEqualTo(byteArrayOf(1, 2, 3))
        decodeDynamic(fields, mapOf("eventTime" to 1L), systemTime.path)
            .assert().isEqualTo(Instant.ofEpochMilli(1))
        decodeDynamic(fields, mapOf("applicationTime" to "2026-08-13T00:00:00Z"), applicationTime.path)
            .assert().isEqualTo(Instant.parse("2026-08-13T00:00:00Z"))

        listOf(
            Triple(binary, mapOf("binary" to "%%%"), fields),
            Triple(systemTime.path, mapOf("eventTime" to "2026-08-13T00:00:00Z"), fields),
            Triple(applicationTime.path, mapOf("applicationTime" to 1L), fields),
            Triple(
                systemTime.path,
                mapOf("eventTime" to BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                fields,
            ),
        ).forEach { (field, source, schemas) ->
            assertResultInvalid(assertThrows { decodeDynamic(schemas, source, field) })
        }
    }

    @Test
    fun `non-finite decimals and typed conversion errors are low information`() {
        val decimal = QueryFieldSchema(VALUE, QueryFieldValueKind.DECIMAL, nullable = false)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            assertResultInvalid(assertThrows { decodeDynamic(listOf(decimal), mapOf("value" to value), VALUE) })
        }
        val string = QueryFieldSchema(VALUE, QueryFieldValueKind.STRING, nullable = false)
        val typed = QueryPlanResultShape.Typed(IntValueResult::class.java, setOf(VALUE))
        val error = assertThrows<QueryException> {
            decoder(listOf(string)).decode<IntValueResult>(
                mapOf("value" to "not-an-int"),
                typed,
                mapOf(VALUE to "value"),
            )
        }
        assertResultInvalid(error)
    }

    private fun decodeDynamic(
        fields: Collection<QueryFieldSchema>,
        source: Map<String, Any?>,
        field: LogicalField,
    ): Any? = decoder(fields).decode<DynamicDocument>(
        source,
        QueryPlanResultShape.Dynamic(setOf(field)),
        mapOf(field to field.value),
    )[field.value]

    private fun decoder(fields: Collection<QueryFieldSchema>): ElasticsearchQueryResultDecoder =
        ElasticsearchQueryResultDecoder(ElasticsearchQueryFieldBinding.bind(QuerySchema(TARGET, fields)))

    private fun assertResultInvalid(error: QueryException) {
        error.code.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
        error.stage.assert().isEqualTo(QueryStage.EXECUTION)
        error.reason.assert().isEqualTo(QueryErrorReason.RESULT_INVALID)
        error.message.assert().isEqualTo("RESULT_VALIDATION_FAILED:EXECUTION:RESULT_INVALID")
        error.cause.assert().isNull()
    }

    data class NullableProfileResult(val profile: ProfileResult?)
    data class ProfileResult(val city: String)
    data class ItemsResult(val items: List<ItemResult>)
    data class ItemResult(val sku: String, val quantity: Long)
    data class IntValueResult(val value: Int)
    data class SparseCityResult(val profile: SparseProfile)
    data class SparseProfile(val address: SparseAddress)
    data class SparseAddress(val city: String?)

    private companion object {
        const val LEGACY_TYPED_MARKER = "me.ahoo.wow.query.compat.LegacyTypedDynamicDocumentMarker"
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("elasticsearch-query-decoder", "value"),
            QueryDocumentKind.SNAPSHOT,
        )
        val VALUE = LogicalField("value")
        val OTHER = LogicalField("other")
    }
}
