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

import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class MongoQueryPlanCompilerTest {
    private val compiler = MongoQueryPlanCompiler(
        binding = MongoQueryFieldBinding.bind(PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)),
        nativeTemplates = MongoNativeQueryTemplateRegistry()
    )

    @Test
    fun `not-in requires field existence and never matches missing`() {
        val filter = compiler.filter(
            PredicateExpression(
                field = PortableQueryDataset.NULLABLE_TEXT,
                operator = PortableOperator.NOT_IN,
                values = listOf(QueryValue.StringValue("blocked"))
            )
        )

        document(filter).assert().isEqualTo(
            Document(
                "\$and",
                listOf(
                    Document("nullableText", Document("\$exists", true)),
                    Document("nullableText", Document("\$nin", listOf("blocked")))
                )
            ).toBsonDocument(Document::class.java, MongoClientSettings.getDefaultCodecRegistry())
        )
    }

    @Test
    fun `equality null and membership preserve physical presence`() {
        document(predicate(PortableOperator.EQ, QueryValue.NullValue)).assert().isEqualTo(
            and(Document("nullableText", Document("\$exists", true)), Document("nullableText", null))
        )
        document(predicate(PortableOperator.NE, QueryValue.StringValue("blocked"))).assert().isEqualTo(
            and(
                Document("nullableText", Document("\$exists", true)),
                Document("nullableText", Document("\$ne", "blocked"))
            )
        )
        document(predicate(PortableOperator.IN, QueryValue.NullValue)).assert().isEqualTo(
            and(
                Document("nullableText", Document("\$exists", true)),
                Document("nullableText", Document("\$in", listOf(null)))
            )
        )
    }

    @Test
    fun `null and exists operators remain orthogonal`() {
        document(predicate(PortableOperator.NULL)).assert().isEqualTo(
            and(Document("nullableText", Document("\$exists", true)), Document("nullableText", null))
        )
        document(predicate(PortableOperator.NOT_NULL)).assert().isEqualTo(
            and(
                Document("nullableText", Document("\$exists", true)),
                Document("nullableText", Document("\$ne", null))
            )
        )
        document(predicate(PortableOperator.EXISTS, QueryValue.BooleanValue(false))).assert().isEqualTo(
            bson(Document("nullableText", Document("\$exists", false)))
        )
    }

    @Test
    fun `boolean and collection membership compile without scalar coercion`() {
        document(
            PredicateExpression(
                PortableQueryDataset.ENABLED,
                PortableOperator.TRUE,
                emptyList()
            )
        ).assert().isEqualTo(bson(Document("enabled", true)))
        document(
            PredicateExpression(
                PortableQueryDataset.TAGS,
                PortableOperator.ALL_IN,
                listOf(QueryValue.StringValue("red"), QueryValue.StringValue("blue"))
            )
        ).assert().isEqualTo(
            bson(Document("labels", Document("\$all", listOf("red", "blue"))))
        )
    }

    @Test
    fun `range and between preserve canonical numeric and instant values`() {
        val lower = Instant.parse("2026-01-01T00:00:00Z")
        val upper = Instant.parse("2026-01-31T00:00:00Z")
        document(
            PredicateExpression(
                PortableQueryDataset.CREATED_AT,
                PortableOperator.BETWEEN,
                listOf(QueryValue.InstantValue(lower), QueryValue.InstantValue(upper))
            )
        ).assert().isEqualTo(
            and(
                Document("createdAt", Document("\$exists", true)),
                Document("createdAt", Document("\$gte", lower.toString())),
                Document("createdAt", Document("\$lte", upper.toString()))
            )
        )
        document(
            PredicateExpression(
                PortableQueryDataset.SCORE,
                PortableOperator.GT,
                listOf(QueryValue.DecimalValue(BigDecimal("10.50")))
            )
        ).assert().isEqualTo(
            and(
                Document("score", Document("\$exists", true)),
                Document("score", Document("\$gt", BigDecimal("10.50")))
            )
        )
    }

    @Test
    fun `system time operands use serializer epoch millis encoding`() {
        val eventTime = Instant.parse("2026-02-01T00:00:00Z")

        document(
            PredicateExpression(
                LogicalField("eventTime"),
                PortableOperator.EQ,
                listOf(QueryValue.InstantValue(eventTime))
            )
        ).assert().isEqualTo(bson(Document("eventTime", eventTime.toEpochMilli())))
    }

    @Test
    fun `literal string operators escape every regex metacharacter and preserve mode`() {
        val literal = "a\\^\$.|?*+()[]{}"
        val escaped = "a\\\\\\^\\\$\\.\\|\\?\\*\\+\\(\\)\\[\\]\\{\\}"
        document(
            PredicateExpression(
                PortableQueryDataset.TITLE,
                PortableOperator.CONTAINS,
                listOf(QueryValue.StringValue(literal)),
                StringComparisonMode.CASE_INSENSITIVE
            )
        ).assert().isEqualTo(document(Filters.regex("title", escaped, "i")))
        document(
            PredicateExpression(
                PortableQueryDataset.TITLE,
                PortableOperator.STARTS_WITH,
                listOf(QueryValue.StringValue(literal)),
                StringComparisonMode.CASE_SENSITIVE
            )
        ).assert().isEqualTo(document(Filters.regex("title", "^$escaped")))
        document(
            PredicateExpression(
                PortableQueryDataset.TITLE,
                PortableOperator.ENDS_WITH,
                listOf(QueryValue.StringValue(literal))
            )
        ).assert().isEqualTo(document(Filters.regex("title", "$escaped\$")))
    }

    @Test
    fun `logical expressions and match constants compile exhaustively`() {
        val left = PredicateExpression(
            PortableQueryDataset.ENABLED,
            PortableOperator.TRUE,
            emptyList()
        )
        val right = PredicateExpression(
            PortableQueryDataset.STATUS,
            PortableOperator.EQ,
            listOf(QueryValue.EnumValue("DONE"))
        )
        document(PortableLogicalExpression(LogicalOperator.AND, listOf(left, right))).assert().isEqualTo(
            document(Filters.and(Filters.eq("enabled", true), Filters.eq("status", "DONE")))
        )
        document(PortableLogicalExpression(LogicalOperator.OR, listOf(left, right))).assert().isEqualTo(
            document(Filters.or(Filters.eq("enabled", true), Filters.eq("status", "DONE")))
        )
        document(PortableLogicalExpression(LogicalOperator.NOR, listOf(left, right))).assert().isEqualTo(
            document(Filters.nor(Filters.eq("enabled", true), Filters.eq("status", "DONE")))
        )
        document(MatchAll).assert().isEqualTo(bson(Document()))
        document(MatchNone).assert().isEqualTo(bson(Document("\$expr", Document("\$eq", listOf(1, 0)))))
    }

    @Test
    fun `element match compiles child paths relative to the same array element`() {
        val expression = ElementMatchExpression(
            PortableQueryDataset.ITEMS,
            PortableLogicalExpression(
                LogicalOperator.AND,
                listOf(
                    PredicateExpression(
                        LogicalField("sku"),
                        PortableOperator.EQ,
                        listOf(QueryValue.StringValue("A"))
                    ),
                    PredicateExpression(
                        LogicalField("quantity"),
                        PortableOperator.GTE,
                        listOf(QueryValue.IntegerValue(2))
                    )
                )
            )
        )

        document(expression).assert().isEqualTo(
            document(
                Filters.elemMatch(
                    "items",
                    Filters.and(
                        Filters.eq("sku", "A"),
                        Filters.and(
                            Filters.exists("quantity", true),
                            Filters.gte("quantity", 2L)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `full text remains a text capability rather than a regex fallback`() {
        val expression = FullTextExpression(
            QueryCapabilityId("full-text"),
            "literal words",
            setOf(PortableQueryDataset.TITLE)
        )

        document(expression).assert().isEqualTo(document(Filters.text("literal words")))
    }

    @Test
    fun `native template receives immutable typed parameters`() {
        var received: Map<String, QueryValue>? = null
        val nativeCompiler = MongoQueryPlanCompiler(
            MongoQueryFieldBinding.bind(PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)),
            MongoNativeQueryTemplateRegistry(
                mapOf(
                    "tenant-eq" to MongoNativeQueryTemplate { parameters ->
                        received = parameters
                        Filters.eq("tenantId", (parameters.getValue("tenant") as QueryValue.StringValue).value)
                    }
                )
            )
        )
        val expression = NativeExpression(
            capabilityId = QueryCapabilityId("x-wow:mongo-native"),
            backendId = "mongo",
            templateId = "tenant-eq",
            parameters = mapOf("tenant" to QueryValue.StringValue("tenant-1")),
            declaredFields = setOf(LogicalField("tenantId"))
        )

        document(nativeCompiler.filter(expression)).assert().isEqualTo(document(Filters.eq("tenantId", "tenant-1")))
        received.assert().isEqualTo(expression.parameters)
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (received as MutableMap<String, QueryValue>)["tenant"] = QueryValue.StringValue("changed")
        }
    }

    @Test
    fun `native capability mismatch and unknown template fail before execution`() {
        val template = MongoNativeQueryTemplate { Filters.empty() }
        val nativeCompiler = MongoQueryPlanCompiler(
            MongoQueryFieldBinding.bind(PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)),
            MongoNativeQueryTemplateRegistry(mapOf("registered" to template))
        )
        listOf(
            NativeExpression(
                QueryCapabilityId("x-wow:mongo-native"),
                "elasticsearch",
                "registered",
                emptyMap(),
                setOf(PortableQueryDataset.TITLE)
            ),
            NativeExpression(
                QueryCapabilityId("x-wow:mongo-native"),
                "mongo",
                "missing",
                emptyMap(),
                setOf(PortableQueryDataset.TITLE)
            ),
            NativeExpression(
                QueryCapabilityId("x-wow:another-native"),
                "mongo",
                "registered",
                emptyMap(),
                setOf(PortableQueryDataset.TITLE)
            )
        ).forEach { expression ->
            val error = assertThrows<me.ahoo.wow.api.query.error.QueryException> {
                nativeCompiler.filter(expression)
            }
            error.code.assert().isEqualTo(me.ahoo.wow.api.query.error.QueryErrorCode.UNSUPPORTED_CAPABILITY)
        }
    }

    @Test
    fun `native registry snapshots registrations and redacts identifiers`() {
        val registrations = linkedMapOf("secret-template" to MongoNativeQueryTemplate { Filters.empty() })
        val registry = MongoNativeQueryTemplateRegistry(registrations)
        registrations.clear()

        registry.template("secret-template").assert().isNotNull()
        registry.toString().contains("secret-template").assert().isFalse()
        registry.toString().assert().isEqualTo("MongoNativeQueryTemplateRegistry(templateCount=1)")
    }

    private fun predicate(operator: PortableOperator, vararg values: QueryValue): PredicateExpression =
        PredicateExpression(PortableQueryDataset.NULLABLE_TEXT, operator, values.toList())

    private fun document(expression: me.ahoo.wow.api.query.expression.QueryExpression) =
        document(compiler.filter(expression))

    private fun document(filter: org.bson.conversions.Bson) =
        filter.toBsonDocument(Document::class.java, MongoClientSettings.getDefaultCodecRegistry())

    private fun bson(document: Document) =
        document.toBsonDocument(Document::class.java, MongoClientSettings.getDefaultCodecRegistry())

    private fun and(vararg operands: Document) = bson(Document("\$and", operands.toList()))
}
