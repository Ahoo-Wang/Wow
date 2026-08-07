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
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryInput
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.function.Consumer

class RawAdmissionGuardTest {

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val limits = QueryAdmissionLimits(
        maxConditionDepth = 4,
        maxConditionNodes = 12,
        maxChildrenPerNode = 4,
        maxFieldLength = 24,
        maxStringLength = 16,
        maxCollectionSize = 4,
        maxObjectFields = 4,
        maxValueDepth = 4,
        maxByteArrayLength = 4,
        maxProjectionFields = 4,
        maxSortFields = 3,
        maxOptions = 3,
    )
    private val guard = RawAdmissionGuard(limits)

    @Test
    fun `should read each query getter once and freeze every mutable boundary`() {
        val bytes = byteArrayOf(1, 2)
        val objectValue = linkedMapOf<String, Any?>("bytes" to bytes)
        val oneShot = OneShotIterable(listOf(objectValue))
        val projectionFields = mutableListOf("state.name")
        val sorts = mutableListOf(Sort("state.name", Sort.Direction.ASC))
        val query = SingleReadQuery(
            conditionValue = Condition("state.items", Operator.IN, oneShot),
            projectionValue = Projection(include = projectionFields),
            sortValue = sorts,
        )

        val admitted = guard.admit(singleInvocation(query))
        projectionFields += "late"
        sorts.clear()
        objectValue.clear()
        bytes[0] = 9

        query.conditionReads.assert().isEqualTo(1)
        query.projectionReads.assert().isEqualTo(1)
        query.sortReads.assert().isEqualTo(1)
        oneShot.iteratorReads.assert().isEqualTo(1)

        val admittedQuery = (admitted.input as AdmittedQueryInput.Single).query
        admittedQuery.projection.include.assert().containsExactly("state.name")
        admittedQuery.sort.map { it.field }.assert().containsExactly("state.name")
        admittedQuery.condition.queryValue().assert().isEqualTo(
            NormalizedValue.ListValue(
                listOf(
                    NormalizedValue.ObjectValue(
                        mapOf("bytes" to NormalizedValue.Bytes(byteArrayOf(1, 2))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `should canonicalize equivalent numeric values during admission`() {
        val condition = Condition(
            "state.numbers",
            Operator.ALL_IN,
            OneShotIterable(listOf(1, 1L, 1.0, BigDecimal("1.00"))),
        )

        val admitted = guard.admit(singleInvocation(SingleReadQuery(condition)))
        val admittedCondition = (admitted.input as AdmittedQueryInput.Single).query.condition
        val values = admittedCondition.queryValue() as NormalizedValue.ListValue

        values.values.assert().containsExactly(
            NormalizedValue.Int64(1),
            NormalizedValue.Int64(1),
            NormalizedValue.Int64(1),
            NormalizedValue.Int64(1),
        )
    }

    @Test
    fun `should reject budgets at the exact offending path`() {
        val tooDeep = Condition.and(
            Condition.and(
                Condition.and(
                    Condition.and(Condition.eq("field", "value")),
                ),
            ),
        )
        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.CONDITION_DEPTH_LIMIT_EXCEEDED,
            "$.input.query.condition.children[0].children[0].children[0].children[0]",
        ) {
            guard.admit(singleInvocation(SingleReadQuery(tooDeep)))
        }

        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.STRING_LIMIT_EXCEEDED,
            "$.input.query.condition.value",
        ) {
            guard.admit(
                singleInvocation(
                    SingleReadQuery(Condition.eq("field", "x".repeat(limits.maxStringLength + 1))),
                ),
            )
        }

        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.COLLECTION_LIMIT_EXCEEDED,
            "$.input.query.condition.value",
        ) {
            guard.admit(
                singleInvocation(
                    SingleReadQuery(
                        Condition("field", Operator.IN, OneShotIterable((0..limits.maxCollectionSize).toList())),
                    ),
                ),
            )
        }
    }

    @Test
    fun `should reject condition and value cycles without overflowing the stack`() {
        val children = mutableListOf<Condition>()
        val cyclicCondition = Condition(operator = Operator.AND, children = children)
        children += cyclicCondition

        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.CYCLIC_INPUT,
            "$.input.query.condition.children[0]",
        ) {
            guard.admit(singleInvocation(SingleReadQuery(cyclicCondition)))
        }

        val values = mutableListOf<Any?>()
        values.add(values)
        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.CYCLIC_INPUT,
            "$.input.query.condition.value[0]",
        ) {
            guard.admit(singleInvocation(SingleReadQuery(Condition.eq("field", values))))
        }
    }

    @Test
    fun `should reject malformed operator values with typed errors`() {
        val cases = listOf(
            Condition(operator = Operator.AND) to QueryRejectionCode.INVALID_CHILDREN,
            Condition("field", Operator.BETWEEN, listOf(1)) to QueryRejectionCode.INVALID_VALUE_ARITY,
            Condition("field", Operator.ELEM_MATCH) to QueryRejectionCode.INVALID_CHILDREN,
            Condition(operator = Operator.ID, value = 1) to QueryRejectionCode.INVALID_VALUE_TYPE,
            Condition(operator = Operator.IDS, value = listOf("id", 1)) to QueryRejectionCode.INVALID_VALUE_TYPE,
            Condition("field", Operator.IN, "value") to QueryRejectionCode.INVALID_VALUE_TYPE,
            Condition("field", Operator.EXISTS, "true") to QueryRejectionCode.INVALID_VALUE_TYPE,
            Condition("field", Operator.RECENT_DAYS, 0) to QueryRejectionCode.INVALID_TIME_VALUE,
            Condition("field", Operator.BEFORE_TODAY, 86_400) to QueryRejectionCode.INVALID_TIME_VALUE,
        )

        cases.forEach { (condition, code) ->
            assertThrownBy<QueryRejectedException> {
                guard.admit(singleInvocation(SingleReadQuery(condition)))
            }.satisfies(
                Consumer { error ->
                    error.rejection.category.assert().isEqualTo(QueryRejectionCategory.INVALID_QUERY)
                    error.rejection.code.assert().isEqualTo(code)
                }
            )
        }
    }

    @Test
    fun `should reject unknown or mistyped options and unbound native input`() {
        val cases = listOf(
            Condition(
                "field",
                Operator.CONTAINS,
                "value",
                options = mapOf(Condition.IGNORE_CASE_OPTION_KEY to "true"),
            ) to QueryRejectionCode.INVALID_OPTION_TYPE,
            Condition(
                "field",
                Operator.TODAY,
                options = mapOf("unknown" to true),
            ) to QueryRejectionCode.UNKNOWN_OPTION,
            Condition.eq("field", "value").copy(
                options = mapOf(Condition.IGNORE_CASE_OPTION_KEY to true),
            ) to QueryRejectionCode.OPTION_NOT_ALLOWED,
        )

        cases.forEach { (condition, code) ->
            assertThrownBy<QueryRejectedException> {
                guard.admit(singleInvocation(SingleReadQuery(condition)))
            }.satisfies(
                Consumer { error ->
                    error.rejection.code.assert().isEqualTo(code)
                }
            )
        }

        val raw = guard.admit(singleInvocation(SingleReadQuery(Condition.raw(HostileRawValue()))))
        val rawCondition = (raw.input as AdmittedQueryInput.Single).query.condition
        rawCondition.value.assert().isEqualTo(AdmittedConditionValue.NativeUnbound)
    }

    private fun singleInvocation(query: ISingleQuery): QueryInvocation =
        QueryInvocation(
            target = target,
            operation = QueryOperation.SINGLE,
            resultShape = QueryResultShape.TYPED,
            input = QueryInput.Single(query),
        )

    private fun assertRejected(
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
            }
        )
    }

    private class OneShotIterable<T>(private val values: List<T>) : Iterable<T> {
        var iteratorReads: Int = 0
            private set

        override fun iterator(): Iterator<T> {
            iteratorReads++
            check(iteratorReads == 1) {
                "Iterable can only be consumed once."
            }
            return values.iterator()
        }
    }

    private class HostileRawValue {
        override fun toString(): String = error("RAW driver objects must not be inspected during admission.")
    }

    private class SingleReadQuery(
        private val conditionValue: Condition,
        private val projectionValue: Projection = Projection.ALL,
        private val sortValue: List<Sort> = emptyList(),
    ) : ISingleQuery {
        var conditionReads: Int = 0
            private set
        var projectionReads: Int = 0
            private set
        var sortReads: Int = 0
            private set

        override val condition: Condition
            get() = conditionValue.also {
                conditionReads++
                check(conditionReads == 1)
            }
        override val projection: Projection
            get() = projectionValue.also {
                projectionReads++
                check(projectionReads == 1)
            }
        override val sort: List<Sort>
            get() = sortValue.also {
                sortReads++
                check(sortReads == 1)
            }

        override fun withCondition(newCondition: Condition): ISingleQuery = this

        override fun withProjection(newProjection: Projection): ISingleQuery = this
    }
}

private fun AdmittedCondition.queryValue(): NormalizedValue =
    (value as AdmittedConditionValue.QueryValue).value
