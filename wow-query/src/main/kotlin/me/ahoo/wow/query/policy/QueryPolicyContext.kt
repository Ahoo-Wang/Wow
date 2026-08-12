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

package me.ahoo.wow.query.policy

import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.schema.QuerySchemaView
import java.time.Instant
import java.time.ZoneId

class QueryPolicyContext(
    val target: QueryTarget,
    val operation: QueryOperation,
    val normalizedExpression: QueryExpression,
    val resultShape: QueryPolicyResultShape,
    val invocationScope: QueryInvocationScope,
    val schema: QuerySchemaView,
    val requestBudget: QueryBudgetHint,
    val frozenInstant: Instant,
    val zoneId: ZoneId
) {
    init {
        require(schema.target == target) { "Policy schema target must match the query target." }
    }

    operator fun component1(): QueryTarget = target

    operator fun component2(): QueryOperation = operation

    operator fun component3(): QueryExpression = normalizedExpression

    operator fun component4(): QueryPolicyResultShape = resultShape

    operator fun component5(): QueryInvocationScope = invocationScope

    operator fun component6(): QuerySchemaView = schema

    operator fun component7(): QueryBudgetHint = requestBudget

    operator fun component8(): Instant = frozenInstant

    operator fun component9(): ZoneId = zoneId

    fun copy(
        target: QueryTarget = this.target,
        operation: QueryOperation = this.operation,
        normalizedExpression: QueryExpression = this.normalizedExpression,
        resultShape: QueryPolicyResultShape = this.resultShape,
        invocationScope: QueryInvocationScope = this.invocationScope,
        schema: QuerySchemaView = this.schema,
        requestBudget: QueryBudgetHint = this.requestBudget,
        frozenInstant: Instant = this.frozenInstant,
        zoneId: ZoneId = this.zoneId
    ): QueryPolicyContext = QueryPolicyContext(
        target,
        operation,
        normalizedExpression,
        resultShape,
        invocationScope,
        schema,
        requestBudget,
        frozenInstant,
        zoneId
    )

    override fun equals(other: Any?): Boolean = other is QueryPolicyContext &&
        target == other.target && operation == other.operation && normalizedExpression == other.normalizedExpression &&
        resultShape == other.resultShape && invocationScope == other.invocationScope && schema == other.schema &&
        requestBudget == other.requestBudget && frozenInstant == other.frozenInstant && zoneId == other.zoneId

    override fun hashCode(): Int = listOf(
        target,
        operation,
        normalizedExpression,
        resultShape,
        invocationScope,
        schema,
        requestBudget,
        frozenInstant,
        zoneId
    ).hashCode()

    override fun toString(): String =
        "QueryPolicyContext(operation=$operation, target=<redacted>, normalizedExpression=<redacted>, " +
            "resultShape=${resultShape.safeKind()}, invocationScope=<redacted>, schema=<redacted>, " +
            "requestBudget=<redacted>, frozenInstant=<redacted>, zoneId=<redacted>)"

    private fun QueryPolicyResultShape.safeKind(): String = when (this) {
        QueryPolicyResultShape.Count -> "COUNT"
        QueryPolicyResultShape.Dynamic -> "DYNAMIC"
        is QueryPolicyResultShape.Typed -> "TYPED"
    }
}
