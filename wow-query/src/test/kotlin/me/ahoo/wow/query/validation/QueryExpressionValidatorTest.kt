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

package me.ahoo.wow.query.validation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class QueryExpressionValidatorTest {
    private val limits = QueryStructureLimits(
        maxDepth = 8,
        maxNodes = 16,
        maxMembershipItems = 4,
        maxNativeParameterBytes = 64
    )
    private val validator = QueryExpressionValidator(limits)
    private val target = queryTarget()
    private val fullText = QueryCapabilityId("full-text")
    private val native = QueryCapabilityId("x-acme:native")
    private val schema = QuerySchema(
        target,
        listOf(
            QueryFieldSchema.string(LogicalField("name"), nullable = false).copy(
                sortable = true,
                capabilities = setOf(fullText),
                stringOptions = me.ahoo.wow.query.schema.StringQueryOptions(
                    comparisonModes = setOf(
                        StringComparisonMode.DEFAULT,
                        StringComparisonMode.CASE_INSENSITIVE
                    ),
                    maxLength = 8
                )
            ),
            QueryFieldSchema.string(LogicalField("optional"), nullable = true),
            QueryFieldSchema(
                path = LogicalField("age"),
                valueKind = QueryFieldValueKind.INTEGER,
                nullable = false
            ),
            QueryFieldSchema(
                path = LogicalField("amount"),
                valueKind = QueryFieldValueKind.DECIMAL,
                nullable = false
            ),
            QueryFieldSchema(
                path = LogicalField("enabled"),
                valueKind = QueryFieldValueKind.BOOLEAN,
                nullable = false
            ),
            QueryFieldSchema(
                path = LogicalField("status"),
                valueKind = QueryFieldValueKind.ENUM,
                nullable = false
            ),
            QueryFieldSchema(
                path = LogicalField("createdAt"),
                valueKind = QueryFieldValueKind.TIME,
                nullable = false
            ),
            QueryFieldSchema(
                path = LogicalField("payload"),
                valueKind = QueryFieldValueKind.BINARY,
                nullable = false
            ),
            QueryFieldSchema(
                path = LogicalField("tags"),
                valueKind = QueryFieldValueKind.STRING,
                nullable = false,
                collectionKind = QueryCollectionKind.SCALAR
            ),
            QueryFieldSchema(
                path = LogicalField("lines"),
                valueKind = QueryFieldValueKind.OBJECT,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT,
                elementMatchEnabled = true
            ),
            QueryFieldSchema.string(LogicalField("lines.sku"), nullable = false),
            QueryFieldSchema(
                path = LogicalField("hiddenLines"),
                valueKind = QueryFieldValueKind.OBJECT,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT,
                queryable = false,
                elementMatchEnabled = true,
                operators = emptySet()
            ),
            QueryFieldSchema.string(LogicalField("hiddenLines.sku"), nullable = false),
            QueryFieldSchema.string(LogicalField("nativeField"), nullable = false).copy(
                capabilities = setOf(native)
            ),
            QueryFieldSchema.string(LogicalField("notQueryable"), nullable = false).copy(
                queryable = false,
                operators = emptySet()
            )
        )
    )

    @Test
    fun `covers every portable operator legal and illegal arity`() {
        val one = listOf<QueryValue>(QueryValue.StringValue("one"))
        val two = one + QueryValue.StringValue("two")
        val fixtures = mapOf(
            PortableOperator.EQ to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.NE to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.GT to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.LT to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.GTE to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.LTE to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.CONTAINS to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.STARTS_WITH to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.ENDS_WITH to ArityFixture(one, listOf(emptyList(), two)),
            PortableOperator.EXISTS to ArityFixture(
                listOf(QueryValue.BooleanValue(true)),
                listOf(emptyList(), two, one)
            ),
            PortableOperator.BETWEEN to ArityFixture(two, listOf(one, two + QueryValue.StringValue("three"))),
            PortableOperator.IN to ArityFixture(one, listOf(emptyList())),
            PortableOperator.NOT_IN to ArityFixture(one, listOf(emptyList())),
            PortableOperator.ALL_IN to ArityFixture(one, listOf(emptyList())),
            PortableOperator.NULL to ArityFixture(emptyList(), listOf(one)),
            PortableOperator.NOT_NULL to ArityFixture(emptyList(), listOf(one)),
            PortableOperator.TRUE to ArityFixture(emptyList(), listOf(one)),
            PortableOperator.FALSE to ArityFixture(emptyList(), listOf(one))
        )

        fixtures.keys.assert().isEqualTo(PortableOperator.entries.toSet())
        fixtures.forEach { (operator, fixture) ->
            validator.validateStructure(predicate(operator, fixture.validValues))
            fixture.invalidValues.forEach { values ->
                assertInvalid { validator.validateStructure(predicate(operator, values)) }
            }
        }
    }

    @Test
    fun `enforces depth node membership and native parameter byte limits at exact boundaries`() {
        QueryExpressionValidator(limits.copy(maxDepth = 1)).validateStructure(MatchAll)
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxDepth = 1)).validateStructure(
                PortableLogicalExpression(LogicalOperator.AND, listOf(MatchAll))
            )
        }

        QueryExpressionValidator(limits.copy(maxNodes = 2)).validateStructure(
            PortableLogicalExpression(LogicalOperator.AND, listOf(MatchAll))
        )
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxNodes = 1)).validateStructure(
                PortableLogicalExpression(LogicalOperator.AND, listOf(MatchAll))
            )
        }

        validator.validateStructure(predicate(PortableOperator.IN, List(4) { QueryValue.StringValue("v") }))
        assertInvalid {
            validator.validateStructure(predicate(PortableOperator.IN, List(5) { QueryValue.StringValue("v") }))
        }

        val fiveBytes = NativeExpression(
            native,
            "mongo",
            "safe-template",
            mapOf("k" to QueryValue.StringValue("v")),
            setOf(LogicalField("nativeField"))
        )
        QueryExpressionValidator(limits.copy(maxNativeParameterBytes = 5)).validateStructure(fiveBytes)
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxNativeParameterBytes = 4)).validateStructure(fiveBytes)
        }
    }

    @Test
    fun `counts membership items across the complete expression`() {
        val expression = PortableLogicalExpression(
            LogicalOperator.AND,
            listOf(
                predicate(PortableOperator.IN, List(2) { QueryValue.StringValue("v") }),
                predicate(PortableOperator.NOT_IN, List(3) { QueryValue.StringValue("v") })
            )
        )

        QueryExpressionValidator(limits.copy(maxMembershipItems = 5)).validateStructure(expression)
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxMembershipItems = 4)).validateStructure(expression)
        }
    }

    @Test
    fun `estimates every native value content and container cost deterministically`() {
        val fixtures = listOf(
            NativeBytesFixture(QueryValue.BooleanValue(true), 5),
            NativeBytesFixture(QueryValue.IntegerValue(1), 12),
            NativeBytesFixture(QueryValue.FloatingValue(1.5), 12),
            NativeBytesFixture(QueryValue.DecimalValue(BigDecimal("1.20")), 8),
            NativeBytesFixture(QueryValue.StringValue("é"), 6),
            NativeBytesFixture(QueryValue.InstantValue(Instant.EPOCH), 24),
            NativeBytesFixture(QueryValue.EnumValue("OPEN"), 8),
            NativeBytesFixture(QueryValue.BinaryValue(byteArrayOf(1, 2, 3)), 7),
            NativeBytesFixture(QueryValue.NullValue, 4),
            NativeBytesFixture(QueryValue.ListValue(listOf(QueryValue.StringValue("x"))), 8),
            NativeBytesFixture(
                QueryValue.ObjectValue(mapOf("键" to QueryValue.StringValue("y"))),
                11
            )
        )

        fixtures.forEach { fixture ->
            val expression = NativeExpression(
                native,
                "mongo",
                "template",
                mapOf("k" to fixture.value),
                setOf(LogicalField("nativeField"))
            )
            QueryExpressionValidator(limits.copy(maxNativeParameterBytes = fixture.expectedBytes))
                .validateStructure(expression)
            assertInvalid {
                QueryExpressionValidator(limits.copy(maxNativeParameterBytes = fixture.expectedBytes - 1))
                    .validateStructure(expression)
            }
        }
    }

    @Test
    fun `rejects extreme decimal content without materializing it`() {
        val expression = NativeExpression(
            native,
            "mongo",
            "template",
            mapOf("k" to QueryValue.DecimalValue(BigDecimal("1E+2147483647"))),
            setOf(LogicalField("nativeField"))
        )

        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertInvalid {
                QueryExpressionValidator(limits.copy(maxNativeParameterBytes = 64)).validateStructure(expression)
            }
        }
    }

    @Test
    fun `counts every expression kind and all native parameters across the request`() {
        val first = nativeExpression("a")
        val second = nativeExpression("b")
        val expression = LogicalExpression(
            LogicalOperator.AND,
            listOf(
                first,
                FullTextExpression(fullText, "query", setOf(LogicalField("name"))),
                ElementMatchExpression(
                    LogicalField("lines"),
                    predicate("sku", PortableOperator.EQ, QueryValue.StringValue("one"))
                ),
                second
            )
        )

        QueryExpressionValidator(limits.copy(maxNodes = 6, maxNativeParameterBytes = 10))
            .validateStructure(expression)
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxNodes = 5, maxNativeParameterBytes = 10))
                .validateStructure(expression)
        }
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxNodes = 6, maxNativeParameterBytes = 9))
                .validateStructure(expression)
        }
    }

    @Test
    fun `validates deeply nested logical expressions without recursion`() {
        var expression: PortableExpression = MatchAll
        repeat(5_000) {
            expression = PortableLogicalExpression(LogicalOperator.AND, listOf(expression))
        }
        val deepValidator = QueryExpressionValidator(
            limits.copy(maxDepth = 5_001, maxNodes = 5_001)
        )

        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            deepValidator.validateStructure(expression)
            deepValidator.validateSchema(expression, schema)
        }
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxDepth = 5_000, maxNodes = 5_001))
                .validateStructure(expression)
        }
    }

    @Test
    fun `validates deeply nested native values without recursion or overflow`() {
        var value: QueryValue = QueryValue.StringValue("leaf")
        repeat(5_000) {
            value = QueryValue.ListValue(listOf(value))
        }
        val expression = NativeExpression(
            native,
            "mongo",
            "deep-template",
            mapOf("root" to value),
            setOf(LogicalField("nativeField"))
        )

        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            QueryExpressionValidator(limits.copy(maxNativeParameterBytes = 20_000)).validateStructure(expression)
        }
        assertInvalid {
            QueryExpressionValidator(limits.copy(maxNativeParameterBytes = 10)).validateStructure(expression)
        }
    }

    @Test
    fun `validates schema fields operator type collection nullability and string modes`() {
        val valid = listOf(
            predicate("age", PortableOperator.EQ, QueryValue.IntegerValue(1)),
            predicate("amount", PortableOperator.EQ, QueryValue.IntegerValue(1)),
            predicate("amount", PortableOperator.EQ, QueryValue.FloatingValue(1.5)),
            predicate("amount", PortableOperator.EQ, QueryValue.DecimalValue(BigDecimal("1.5"))),
            predicate("enabled", PortableOperator.TRUE),
            predicate("status", PortableOperator.EQ, QueryValue.EnumValue("OPEN")),
            predicate("status", PortableOperator.EQ, QueryValue.StringValue("OPEN")),
            predicate("createdAt", PortableOperator.EQ, QueryValue.InstantValue(Instant.EPOCH)),
            predicate("payload", PortableOperator.EQ, QueryValue.BinaryValue(byteArrayOf(1))),
            predicate("tags", PortableOperator.ALL_IN, QueryValue.StringValue("blue")),
            predicate("optional", PortableOperator.EQ, QueryValue.NullValue),
            predicate("name", PortableOperator.EXISTS, QueryValue.BooleanValue(true)),
            PredicateExpression(
                LogicalField("name"),
                PortableOperator.CONTAINS,
                listOf(QueryValue.StringValue("wow")),
                StringComparisonMode.CASE_INSENSITIVE
            )
        )
        valid.forEach {
            validator.validateStructure(it)
            validator.validateSchema(it, schema)
        }

        val invalid = listOf(
            predicate("ignored", PortableOperator.EQ, QueryValue.StringValue("secret")),
            predicate("notQueryable", PortableOperator.EQ, QueryValue.StringValue("value")),
            predicate("age", PortableOperator.CONTAINS, QueryValue.StringValue("one")),
            predicate("age", PortableOperator.EQ, QueryValue.StringValue("one")),
            predicate("age", PortableOperator.EQ, QueryValue.ListValue(listOf(QueryValue.IntegerValue(1)))),
            predicate("tags", PortableOperator.ALL_IN, QueryValue.ListValue(listOf(QueryValue.StringValue("blue")))),
            predicate("age", PortableOperator.EQ, QueryValue.NullValue),
            predicate("optional", PortableOperator.GT, QueryValue.NullValue),
            predicate("name", PortableOperator.EQ, QueryValue.StringValue("too-long-value")),
            PredicateExpression(
                LogicalField("name"),
                PortableOperator.CONTAINS,
                listOf(QueryValue.StringValue("wow")),
                StringComparisonMode.CASE_SENSITIVE
            )
        )
        invalid.forEach { assertInvalid { validator.validateSchema(it, schema) } }
    }

    @Test
    fun `resolves element match child fields relative to the object collection logical path`() {
        val valid = ElementMatchExpression(
            LogicalField("lines"),
            predicate("sku", PortableOperator.EQ, QueryValue.StringValue("one"))
        )

        validator.validateSchema(valid, schema)
        assertInvalid {
            validator.validateSchema(
                ElementMatchExpression(
                    LogicalField("tags"),
                    predicate("sku", PortableOperator.EQ, QueryValue.StringValue("one"))
                ),
                schema
            )
        }
        assertInvalid {
            validator.validateSchema(
                ElementMatchExpression(
                    LogicalField("hiddenLines"),
                    predicate("sku", PortableOperator.EQ, QueryValue.StringValue("one"))
                ),
                schema
            )
        }
        assertInvalid {
            validator.validateSchema(
                ElementMatchExpression(
                    LogicalField("lines"),
                    predicate("lines.sku", PortableOperator.EQ, QueryValue.StringValue("one"))
                ),
                schema
            )
        }
    }

    @Test
    fun `requires every full text and native declared field to declare the capability`() {
        validator.validateSchema(
            FullTextExpression(fullText, "query", setOf(LogicalField("name"))),
            schema
        )
        validator.validateSchema(nativeExpression("v"), schema)

        listOf<QueryExpression>(
            FullTextExpression(fullText, "query", setOf(LogicalField("name"), LogicalField("ignored"))),
            FullTextExpression(fullText, "query", setOf(LogicalField("name"), LogicalField("age"))),
            NativeExpression(
                native,
                "mongo",
                "template",
                emptyMap(),
                setOf(LogicalField("nativeField"), LogicalField("ignored"))
            ),
            NativeExpression(
                native,
                "mongo",
                "template",
                emptyMap(),
                setOf(LogicalField("nativeField"), LogicalField("name"))
            )
        ).forEach { assertInvalid { validator.validateSchema(it, schema) } }
    }

    @Test
    fun `reports every failure with fixed safe validation dimensions`() {
        val sensitiveField = "sensitiveField"
        val sensitiveValue = "sensitiveValue"
        val error = assertThrows<QueryException> {
            validator.validateSchema(
                predicate(sensitiveField, PortableOperator.EQ, QueryValue.StringValue(sensitiveValue)),
                schema
            )
        }

        error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
        error.stage.assert().isEqualTo(QueryStage.VALIDATION)
        error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
        error.message.assert().isEqualTo("INVALID_QUERY:VALIDATION:INVALID_REQUEST")
        error.message.orEmpty().assert().doesNotContain(sensitiveField, sensitiveValue, "safe-template", "schema")
    }

    @Test
    fun `requires every structure limit to be explicitly positive`() {
        val valid = intArrayOf(1, 1, 1, 1)
        valid.indices.forEach { index ->
            assertThrows<IllegalArgumentException> {
                val values = valid.copyOf()
                values[index] = 0
                QueryStructureLimits(values[0], values[1], values[2], values[3].toLong())
            }
            assertThrows<IllegalArgumentException> {
                val values = valid.copyOf()
                values[index] = -1
                QueryStructureLimits(values[0], values[1], values[2], values[3].toLong())
            }
        }
    }

    private fun nativeExpression(value: String): NativeExpression = NativeExpression(
        native,
        "mongo",
        "template",
        mapOf("k" to QueryValue.StringValue(value)),
        setOf(LogicalField("nativeField"))
    )

    private fun predicate(operator: PortableOperator, values: List<QueryValue>): PredicateExpression =
        PredicateExpression(LogicalField("field"), operator, values)

    private fun predicate(
        field: String,
        operator: PortableOperator,
        vararg values: QueryValue
    ): PredicateExpression = PredicateExpression(LogicalField(field), operator, values.toList())

    private fun assertInvalid(action: () -> Unit) {
        val error = assertThrows<QueryException>(action)
        assertAll(
            { error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY) },
            { error.stage.assert().isEqualTo(QueryStage.VALIDATION) },
            { error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST) },
            { error.message.assert().isEqualTo("INVALID_QUERY:VALIDATION:INVALID_REQUEST") }
        )
    }

    private fun queryTarget(): QueryTarget = QueryTarget(
        object : NamedAggregate {
            override val contextName: String = "sales"
            override val aggregateName: String = "order"
        },
        QueryDocumentKind.SNAPSHOT
    )

    private data class ArityFixture(
        val validValues: List<QueryValue>,
        val invalidValues: List<List<QueryValue>>
    )

    private data class NativeBytesFixture(
        val value: QueryValue,
        val expectedBytes: Long
    )
}
