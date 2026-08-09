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

package me.ahoo.wow.query.internal.admission

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryInput
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.format.DateTimeFormatterBuilder
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.UUID
import java.util.function.Consumer

class RawAdmissionGuardBoundaryTest {

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val baseLimits = QueryAdmissionLimits(
        maxConditionDepth = 8,
        maxConditionNodes = 32,
        maxChildrenPerNode = 8,
        maxFieldLength = 24,
        maxStringLength = 16,
        maxCollectionSize = 4,
        maxObjectFields = 4,
        maxValueDepth = 4,
        maxValueNodes = 32,
        maxByteArrayLength = 4,
        maxValuePayloadBytes = 128,
        maxProjectionFields = 4,
        maxSortFields = 4,
        maxOptions = 4,
    )

    @Test
    fun `should enforce cumulative value node budget across sibling conditions`() {
        val guard = RawAdmissionGuard(baseLimits.copy(maxValueNodes = 4))
        guard.admit(
            countInvocation(
                Condition.and(
                    Condition.eq("first", listOf(1, 2)),
                    Condition.eq("second", 3),
                ),
            ),
        )

        assertRejected(
            guard,
            Condition.and(
                Condition.eq("first", listOf(1, 2)),
                Condition.eq("second", 3),
                Condition.eq("third", 4),
            ),
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.VALUE_NODE_LIMIT_EXCEEDED,
            "$.input.condition.children[2].value",
        )
    }

    @Test
    fun `should enforce cumulative UTF-8 payload budget without rejecting the exact boundary`() {
        val guard = RawAdmissionGuard(baseLimits.copy(maxValuePayloadBytes = 19))
        guard.admit(
            countInvocation(
                Condition.and(
                    Condition.eq("first", "éé"),
                    Condition.eq("second", "éé"),
                ),
            ),
        )

        assertRejected(
            guard,
            Condition.and(
                Condition.eq("first", "éé"),
                Condition.eq("second", "ééa"),
            ),
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.PAYLOAD_LIMIT_EXCEEDED,
            "$.input.condition.children[1].value",
        )
    }

    @Test
    fun `should include condition projection and sort fields in the cumulative payload budget`() {
        val exactGuard = RawAdmissionGuard(baseLimits.copy(maxValuePayloadBytes = 8))
        exactGuard.admit(
            singleInvocation(
                SingleQuery(
                    condition = Condition("abc", Operator.NULL),
                    projection = Projection(include = listOf("de")),
                    sort = listOf(Sort("fgh", Sort.Direction.ASC)),
                ),
            ),
        )

        assertInvocationRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.PAYLOAD_LIMIT_EXCEEDED,
            "$.input.query.sort[0].field",
        ) {
            exactGuard.admit(
                singleInvocation(
                    SingleQuery(
                        condition = Condition("abc", Operator.NULL),
                        projection = Projection(include = listOf("de")),
                        sort = listOf(Sort("fghi", Sort.Direction.ASC)),
                    ),
                ),
            )
        }
    }

    @Test
    fun `should reject excessive numeric precision before materialization`() {
        assertRejected(
            RawAdmissionGuard(baseLimits.copy(maxNumericPrecision = 4)),
            Condition.eq("field", BigInteger("12345")),
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.NUMERIC_PRECISION_LIMIT_EXCEEDED,
            "$.input.condition.value",
        )
    }

    @Test
    fun `should reject decimal canonicalization overflow with a typed rejection`() {
        assertRejected(
            RawAdmissionGuard(baseLimits),
            Condition.eq("field", BigDecimal(BigInteger.TEN, Int.MIN_VALUE)),
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_VALUE_TYPE,
            "$.input.condition.value",
        )
    }

    @Test
    fun `should accept exact local value boundaries and reject the next item`() {
        val guard = RawAdmissionGuard(baseLimits)
        guard.admit(countInvocation(Condition.eq("field", byteArrayOf(1, 2, 3, 4))))
        guard.admit(countInvocation(Condition.eq("field", listOf(1, 2, 3, 4))))
        guard.admit(
            countInvocation(
                Condition.eq("field", linkedMapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4)),
            ),
        )
        guard.admit(countInvocation(Condition.eq("field", listOf(listOf(listOf(1))))))

        val cases = listOf(
            Condition.eq("field", byteArrayOf(1, 2, 3, 4, 5)) to
                QueryRejectionCode.BYTE_ARRAY_LIMIT_EXCEEDED,
            Condition.eq("field", listOf(1, 2, 3, 4, 5)) to
                QueryRejectionCode.COLLECTION_LIMIT_EXCEEDED,
            Condition.eq("field", linkedMapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4, "e" to 5)) to
                QueryRejectionCode.OBJECT_LIMIT_EXCEEDED,
            Condition.eq("field", listOf(listOf(listOf(listOf(1))))) to
                QueryRejectionCode.VALUE_DEPTH_LIMIT_EXCEEDED,
        )
        cases.forEach { (condition, code) ->
            assertThrownBy<QueryRejectedException> {
                guard.admit(countInvocation(condition))
            }.satisfies(
                Consumer { error ->
                    error.rejection.category.assert().isEqualTo(QueryRejectionCategory.BUDGET_EXCEEDED)
                    error.rejection.code.assert().isEqualTo(code)
                },
            )
        }
    }

    @Test
    fun `should enforce condition node child and field boundaries`() {
        RawAdmissionGuard(baseLimits.copy(maxConditionNodes = 3)).admit(
            countInvocation(Condition.and(Condition.eq("a", 1), Condition.eq("b", 2))),
        )
        assertRejected(
            RawAdmissionGuard(baseLimits.copy(maxConditionNodes = 3)),
            Condition.and(Condition.eq("a", 1), Condition.eq("b", 2), Condition.eq("c", 3)),
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.CONDITION_NODE_LIMIT_EXCEEDED,
            "$.input.condition.children[2]",
        )

        RawAdmissionGuard(baseLimits.copy(maxChildrenPerNode = 2)).admit(
            countInvocation(Condition.and(Condition.eq("a", 1), Condition.eq("b", 2))),
        )
        assertRejected(
            RawAdmissionGuard(baseLimits.copy(maxChildrenPerNode = 2)),
            Condition.and(Condition.eq("a", 1), Condition.eq("b", 2), Condition.eq("c", 3)),
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.CHILDREN_LIMIT_EXCEEDED,
            "$.input.condition.children",
        )

        RawAdmissionGuard(baseLimits).admit(
            countInvocation(Condition.eq("f".repeat(baseLimits.maxFieldLength), "x")),
        )
        assertRejected(
            RawAdmissionGuard(baseLimits),
            Condition.eq("f".repeat(baseLimits.maxFieldLength + 1), "x"),
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.STRING_LIMIT_EXCEEDED,
            "$.input.condition.field",
        )
    }

    @Test
    fun `should enforce projection and sort boundaries`() {
        val fields = listOf("a", "b")
        val sorts = listOf(
            Sort("a", Sort.Direction.ASC),
            Sort("b", Sort.Direction.DESC),
        )
        val exactGuard = RawAdmissionGuard(baseLimits.copy(maxProjectionFields = 2, maxSortFields = 2))
        exactGuard.admit(singleInvocation(SingleQuery(Condition.ALL, Projection(include = fields), sorts)))
        exactGuard.admit(
            singleInvocation(
                SingleQuery(Condition.ALL, Projection(include = listOf("a"), exclude = listOf("b")), sorts),
            ),
        )

        assertInvocationRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.PROJECTION_LIMIT_EXCEEDED,
            "$.input.query.projection.include",
        ) {
            exactGuard.admit(
                singleInvocation(
                    SingleQuery(Condition.ALL, Projection(include = fields + "c"), sorts),
                ),
            )
        }
        assertInvocationRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.SORT_LIMIT_EXCEEDED,
            "$.input.query.sort",
        ) {
            exactGuard.admit(
                singleInvocation(
                    SingleQuery(
                        Condition.ALL,
                        Projection(include = fields),
                        sorts + Sort("c", Sort.Direction.ASC),
                    ),
                ),
            )
        }
        assertInvocationRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.PROJECTION_LIMIT_EXCEEDED,
            "$.input.query.projection.exclude",
        ) {
            exactGuard.admit(
                singleInvocation(
                    SingleQuery(
                        Condition.ALL,
                        Projection(include = fields, exclude = listOf("c")),
                        sorts,
                    ),
                ),
            )
        }
    }

    @Test
    fun `should enforce option count boundary`() {
        val optionGuard = RawAdmissionGuard(baseLimits.copy(maxOptions = 1))
        optionGuard.admit(
            countInvocation(
                Condition("field", Operator.TODAY, options = mapOf(Condition.ZONE_ID_OPTION_KEY to "UTC")),
            ),
        )
        assertRejected(
            optionGuard,
            Condition(
                "field",
                Operator.TODAY,
                options = linkedMapOf(
                    Condition.ZONE_ID_OPTION_KEY to "UTC",
                    Condition.DATE_PATTERN_OPTION_KEY to "yyyy-MM-dd",
                ),
            ),
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.OPTIONS_LIMIT_EXCEEDED,
            "$.input.condition.options",
        )
    }

    @Test
    fun `should reject pre-normalized values at the raw boundary`() {
        val values = listOf(
            NormalizedValue.Text("text"),
            NormalizedValue.Bytes(byteArrayOf(1)),
            NormalizedValue.ListValue(listOf(NormalizedValue.Int64(1))),
            NormalizedValue.ObjectValue(mapOf("key" to NormalizedValue.Int64(1))),
        )

        values.forEach { value ->
            assertRejected(
                RawAdmissionGuard(baseLimits),
                Condition.eq("field", value),
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionCode.INVALID_VALUE_TYPE,
                "$.input.condition.value",
            )
        }
    }

    @Test
    fun `should validate original IDS element types before canonicalization`() {
        listOf(UUID.randomUUID(), 'x', TestValue.VALUE).forEach { invalidId ->
            assertRejected(
                RawAdmissionGuard(baseLimits),
                Condition(operator = Operator.IDS, value = listOf(invalidId)),
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionCode.INVALID_VALUE_TYPE,
                "$.input.condition.value[0]",
            )
        }
    }

    @Test
    fun `should type reject null collection members injected through Java compatible lists`() {
        assertRejected(
            RawAdmissionGuard(baseLimits),
            Condition(operator = Operator.AND, children = unsafeList(null)),
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_CHILDREN,
            "$.input.condition.children[0]",
        )
        assertInvocationRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_PROJECTION,
            "$.input.query.projection.include[0]",
        ) {
            RawAdmissionGuard(baseLimits).admit(
                singleInvocation(
                    SingleQuery(Condition.ALL, Projection(include = unsafeList(null))),
                ),
            )
        }
        assertInvocationRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_SORT,
            "$.input.query.sort[0]",
        ) {
            RawAdmissionGuard(baseLimits).admit(
                singleInvocation(SingleQuery(Condition.ALL, sort = unsafeList(null))),
            )
        }
    }

    @Test
    fun `should apply string budget before parsing time deletion and options`() {
        val overLimit = "x".repeat(baseLimits.maxStringLength + 1)
        val oversizedFormatter = DateTimeFormatterBuilder().appendLiteral(overLimit).toFormatter()
        val cases = listOf(
            Condition(operator = Operator.DELETED, value = overLimit) to "$.input.condition.value",
            Condition("field", Operator.BEFORE_TODAY, overLimit) to "$.input.condition.value",
            Condition("field", Operator.TODAY, options = mapOf(Condition.ZONE_ID_OPTION_KEY to overLimit)) to
                "$.input.condition.options['zoneId']",
            Condition("field", Operator.TODAY, options = mapOf(Condition.DATE_PATTERN_OPTION_KEY to overLimit)) to
                "$.input.condition.options['datePattern']",
            Condition("field", Operator.TODAY, options = mapOf(Condition.DATE_PATTERN_OPTION_KEY to oversizedFormatter)) to
                "$.input.condition.options['datePattern']",
        )

        cases.forEach { (condition, path) ->
            assertRejected(
                RawAdmissionGuard(baseLimits),
                condition,
                QueryRejectionCategory.BUDGET_EXCEEDED,
                QueryRejectionCode.STRING_LIMIT_EXCEEDED,
                path,
            )
        }
    }

    @Test
    fun `should count a string date pattern exactly once`() {
        val exactPayloadSize = "field".length + Condition.DATE_PATTERN_OPTION_KEY.length + "yyyy".length
        RawAdmissionGuard(baseLimits.copy(maxValuePayloadBytes = exactPayloadSize.toLong())).admit(
            countInvocation(
                Condition(
                    "field",
                    Operator.TODAY,
                    options = mapOf(Condition.DATE_PATTERN_OPTION_KEY to "yyyy"),
                ),
            ),
        )
    }

    @Test
    fun `should reject unsupported sql date values with a typed rejection`() {
        listOf(
            java.sql.Date.valueOf("2024-03-10"),
            java.sql.Time.valueOf("12:30:00"),
        ).forEach { value ->
            assertRejected(
                RawAdmissionGuard(baseLimits),
                Condition.eq("field", value),
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionCode.INVALID_VALUE_TYPE,
                "$.input.condition.value",
            )
        }
    }

    @Test
    fun `should reject hostile duplicate map entries after a bounded number of reads`() {
        val duplicateMap = DuplicateKeyMap()

        assertRejected(
            RawAdmissionGuard(baseLimits),
            Condition.eq("field", duplicateMap),
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.DUPLICATE_OBJECT_KEY,
            "$.input.condition.value['same']",
        )
        duplicateMap.nextReads.assert().isEqualTo(2)
    }

    @Test
    fun `should keep option rejection path stable across map iteration order`() {
        val optionsA = linkedMapOf<String, Any>(Condition.ZONE_ID_OPTION_KEY to "UTC", "unexpected" to true)
        val optionsB = linkedMapOf<String, Any>("unexpected" to true, Condition.ZONE_ID_OPTION_KEY to "UTC")

        listOf(optionsA, optionsB).forEach { options ->
            assertRejected(
                RawAdmissionGuard(baseLimits),
                Condition("field", Operator.TODAY, options = options),
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionCode.UNKNOWN_OPTION,
                "$.input.condition.options['unexpected']",
            )
        }
    }

    private fun countInvocation(condition: Condition): QueryInvocation =
        QueryInvocation(
            target = target,
            operation = QueryOperation.COUNT,
            resultShape = QueryResultShape.COUNT,
            input = QueryInput.Count(condition),
        )

    @Suppress("UNCHECKED_CAST")
    private fun <T> unsafeList(value: Any?): List<T> = listOf(value) as List<T>

    private fun singleInvocation(query: SingleQuery): QueryInvocation =
        QueryInvocation(
            target = target,
            operation = QueryOperation.SINGLE,
            resultShape = QueryResultShape.TYPED,
            input = QueryInput.Single(query),
        )

    private fun assertInvocationRejected(
        category: QueryRejectionCategory,
        code: QueryRejectionCode,
        path: String,
        action: () -> Unit,
    ) {
        assertThrownBy<QueryRejectedException>(action).satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(path)
            },
        )
    }

    private fun assertRejected(
        guard: RawAdmissionGuard,
        condition: Condition,
        category: QueryRejectionCategory,
        code: QueryRejectionCode,
        path: String,
    ) {
        assertThrownBy<QueryRejectedException> {
            guard.admit(countInvocation(condition))
        }.satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(path)
            },
        )
    }

    private enum class TestValue {
        VALUE,
    }

    private class DuplicateKeyMap : AbstractMap<String, Any?>() {
        var nextReads: Int = 0
            private set

        override val entries: Set<Map.Entry<String, Any?>> = object : AbstractSet<Map.Entry<String, Any?>>() {
            override val size: Int = Int.MAX_VALUE

            override fun iterator(): Iterator<Map.Entry<String, Any?>> = object : Iterator<Map.Entry<String, Any?>> {
                override fun hasNext(): Boolean = true

                override fun next(): Map.Entry<String, Any?> {
                    if (!hasNext()) {
                        throw NoSuchElementException()
                    }
                    nextReads++
                    return SimpleImmutableEntry("same", nextReads)
                }
            }
        }
    }
}
