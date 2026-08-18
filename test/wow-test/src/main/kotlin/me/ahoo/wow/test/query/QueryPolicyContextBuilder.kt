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

package me.ahoo.wow.test.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryPolicyResultShape
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaView
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An immutable builder for a single, deterministic [QueryPolicyContext].
 *
 * Every `with` function returns a new builder. Defaults are fixed constants and
 * never read the system clock, the system zone, Reactor context, or Spring.
 */
class QueryPolicyContextBuilder private constructor(
    val target: QueryTarget,
    private val operation: QueryOperation,
    private val expression: QueryExpression,
    private val resultShape: QueryPolicyResultShape,
    private val authority: QueryAuthorityView,
    private val requestedScope: RequestedQueryScope,
    private val correlationId: String,
    private val schema: QuerySchemaView?,
    private val budget: QueryBudgetHint,
    private val frozenInstant: Instant,
    private val zoneId: ZoneId
) {
    constructor() : this(
        target = DEFAULT_TARGET,
        operation = QueryOperation.SINGLE,
        expression = MatchAll,
        resultShape = QueryPolicyResultShape.Dynamic,
        authority = DEFAULT_AUTHORITY,
        requestedScope = RequestedQueryScope(),
        correlationId = DEFAULT_CORRELATION_ID,
        schema = null,
        budget = QueryBudgetHint(),
        frozenInstant = DEFAULT_FROZEN_INSTANT,
        zoneId = DEFAULT_ZONE_ID
    )

    fun withTarget(target: QueryTarget): QueryPolicyContextBuilder = copy(target = target, schema = null)

    fun withOperation(operation: QueryOperation): QueryPolicyContextBuilder = copy(operation = operation)

    fun withExpression(expression: QueryExpression): QueryPolicyContextBuilder = copy(expression = expression)

    fun withResultShape(resultShape: QueryPolicyResultShape): QueryPolicyContextBuilder =
        copy(resultShape = resultShape)

    fun withAuthority(authority: QueryAuthorityView): QueryPolicyContextBuilder = copy(authority = authority)

    fun withRequestedScope(requestedScope: RequestedQueryScope): QueryPolicyContextBuilder =
        copy(requestedScope = requestedScope)

    fun withCorrelationId(correlationId: String): QueryPolicyContextBuilder = copy(correlationId = correlationId)

    fun withSchema(schema: QuerySchemaView): QueryPolicyContextBuilder {
        require(schema.target == target) { "Policy test schema target must match the builder target." }
        return copy(schema = schema)
    }

    fun withBudget(budget: QueryBudgetHint): QueryPolicyContextBuilder = copy(budget = budget)

    fun withFrozenInstant(frozenInstant: Instant): QueryPolicyContextBuilder = copy(frozenInstant = frozenInstant)

    fun withZoneId(zoneId: ZoneId): QueryPolicyContextBuilder = copy(zoneId = zoneId)

    fun build(): QueryPolicyContext = QueryPolicyContext(
        target = target,
        operation = operation,
        normalizedExpression = expression,
        resultShape = resultShape,
        invocationScope = QueryInvocationScope(authority, requestedScope, correlationId),
        schema = schema ?: QuerySchema(target, emptyList()),
        requestBudget = budget,
        frozenInstant = frozenInstant,
        zoneId = zoneId
    )

    override fun toString(): String =
        "QueryPolicyContextBuilder(operation=$operation, target=<redacted>, expression=<redacted>, " +
            "resultShape=${resultShape.safeKind()}, authority=<redacted>, requestedScope=<redacted>, " +
            "correlationId=<redacted>, schema=<redacted>, budget=<redacted>, frozenInstant=<redacted>, " +
            "zoneId=<redacted>)"

    private fun copy(
        target: QueryTarget = this.target,
        operation: QueryOperation = this.operation,
        expression: QueryExpression = this.expression,
        resultShape: QueryPolicyResultShape = this.resultShape,
        authority: QueryAuthorityView = this.authority,
        requestedScope: RequestedQueryScope = this.requestedScope,
        correlationId: String = this.correlationId,
        schema: QuerySchemaView? = this.schema,
        budget: QueryBudgetHint = this.budget,
        frozenInstant: Instant = this.frozenInstant,
        zoneId: ZoneId = this.zoneId
    ): QueryPolicyContextBuilder = QueryPolicyContextBuilder(
        target,
        operation,
        expression,
        resultShape,
        authority,
        requestedScope,
        correlationId,
        schema,
        budget,
        frozenInstant,
        zoneId
    )

    private fun QueryPolicyResultShape.safeKind(): String = when (this) {
        QueryPolicyResultShape.Count -> "COUNT"
        QueryPolicyResultShape.Dynamic -> "DYNAMIC"
        is QueryPolicyResultShape.ProjectedDynamic -> "PROJECTED_DYNAMIC"
        is QueryPolicyResultShape.Typed -> "TYPED"
    }

    companion object {
        const val DEFAULT_CORRELATION_ID: String = "query-policy-test"

        @JvmField
        val DEFAULT_FROZEN_INSTANT: Instant = Instant.EPOCH

        @JvmField
        val DEFAULT_ZONE_ID: ZoneId = ZoneOffset.UTC

        @JvmField
        val DEFAULT_AUTHORITY: QueryAuthorityView = QueryAuthorityView(
            subjectId = null,
            tenantId = null,
            ownerId = null,
            spaceIds = emptySet(),
            permissions = emptySet()
        )

        @JvmField
        val DEFAULT_TARGET: QueryTarget = QueryTarget(
            object : NamedAggregate {
                override val contextName: String = "test"
                override val aggregateName: String = "query-policy"
            },
            QueryDocumentKind.SNAPSHOT
        )
    }
}
