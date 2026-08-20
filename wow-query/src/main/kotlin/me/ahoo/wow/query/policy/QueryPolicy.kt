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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DeletionScope
import me.ahoo.wow.api.query.LegacyConditionExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryCapabilities
import me.ahoo.wow.api.query.QueryCapabilityId
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.query.schema.QuerySchema
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.Context
import reactor.util.context.ContextView
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant

data class QueryAuthority(
    val subjectId: String? = null,
    val tenantId: String? = null,
    val ownerId: String? = null,
    val spaceIds: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet()
) {
    init {
        require(subjectId == null || subjectId.isNotBlank()) { "subjectId cannot be blank." }
        require(tenantId == null || tenantId.isNotBlank()) { "tenantId cannot be blank." }
        require(ownerId == null || ownerId.isNotBlank()) { "ownerId cannot be blank." }
        require(spaceIds.none(String::isBlank)) { "spaceIds cannot contain blank values." }
        require(permissions.none(String::isBlank)) { "permissions cannot contain blank values." }
    }

    override fun toString(): String = "QueryAuthority(<redacted>)"

    companion object {
        @JvmField
        val ANONYMOUS: QueryAuthority = QueryAuthority()
    }
}

object QueryContexts {
    private val AUTHORITY_KEY = QueryAuthority::class.java

    @JvmStatic
    fun withAuthority(authority: QueryAuthority): (Context) -> Context {
        val snapshot = authority.copy(
            spaceIds = authority.spaceIds.toSet(),
            permissions = authority.permissions.toSet()
        )
        return { context -> context.put(AUTHORITY_KEY, snapshot) }
    }

    internal fun authority(context: ContextView): QueryAuthority =
        context.getOrDefault(AUTHORITY_KEY, QueryAuthority.ANONYMOUS) ?: QueryAuthority.ANONYMOUS
}

enum class QueryOperation {
    FIRST,
    STREAM,
    PAGE,
    COUNT
}

enum class QueryResultKind {
    SNAPSHOT,
    RECORD,
    COUNT
}

internal data class QueryCallContext(
    val authority: QueryAuthority,
    val subscribedAt: Instant
)

data class QueryPolicyContext(
    val target: NamedAggregate,
    val stateType: Class<*>,
    val operation: QueryOperation,
    val resultKind: QueryResultKind,
    val query: Query,
    val authority: QueryAuthority,
    val schema: QuerySchema,
    val subscribedAt: Instant,
    val deadline: Instant?,
    val page: Int? = null,
    val size: Int? = null,
    val limit: Int? = null
) {
    override fun toString(): String =
        "QueryPolicyContext(operation=$operation, resultKind=$resultKind, query=<redacted>, authority=<redacted>)"
}

enum class QueryDecision {
    DENY,
    GRANT,
    ABSTAIN
}

enum class CapabilityDecision {
    DENY,
    GRANT,
    ABSTAIN
}

sealed interface QueryFieldAccess {
    data object Unrestricted : QueryFieldAccess

    data class Restricted(val fields: Set<LogicalField>) : QueryFieldAccess
}

data class QueryAuthorization(
    val decision: QueryDecision = QueryDecision.ABSTAIN,
    val mandatoryFilter: QueryExpression = MatchAll,
    val fieldAccess: QueryFieldAccess = QueryFieldAccess.Unrestricted,
    val maximumBudget: QueryBudget = QueryBudget(),
    val capabilities: Map<QueryCapabilityId, CapabilityDecision> = emptyMap()
) {
    override fun toString(): String = "QueryAuthorization(<redacted>)"
}

fun interface QueryPolicy {
    fun evaluate(context: QueryPolicyContext): Mono<QueryAuthorization>
}

object QueryPolicyPermissions {
    const val QUERY_DELETED_SNAPSHOTS: String = "query:snapshot:deletion"
}

internal class SystemQueryPolicy(
    private val maximumBudget: QueryBudget = QueryBudget()
) : QueryPolicy {
    override fun evaluate(context: QueryPolicyContext): Mono<QueryAuthorization> = Mono.fromSupplier {
        val mandatory = mutableListOf<QueryExpression>()
        authorizeScope(context, mandatory)
        QueryAuthorization(
            decision = QueryDecision.GRANT,
            mandatoryFilter = and(mandatory),
            fieldAccess = QueryFieldAccess.Restricted(context.schema.fields.keys),
            maximumBudget = maximumBudget,
            capabilities = mapOf(
                QueryCapabilities.FULL_TEXT to CapabilityDecision.GRANT,
                QueryCapabilities.LEGACY_BACKEND to CapabilityDecision.GRANT
            )
        )
    }

    private fun authorizeScope(context: QueryPolicyContext, mandatory: MutableList<QueryExpression>) {
        val requested = context.query.scope
        val authority = context.authority
        mandatory += scopeEquals("tenantId", requested.tenantId, authority.tenantId)
        mandatory += scopeEquals("ownerId", requested.ownerId, authority.ownerId)
        when {
            requested.spaceId != null -> {
                val spaceId = checkNotNull(requested.spaceId)
                if (spaceId !in authority.spaceIds) deny()
                mandatory += equals("spaceId", spaceId)
            }

            authority.spaceIds.isNotEmpty() -> mandatory += PredicateExpression(
                LogicalField("spaceId"),
                PredicateOperator.IN,
                authority.spaceIds.sorted().map(JsonNodeFactory.instance::stringNode)
            )
        }
        when (requested.deletion) {
            DeletionScope.DEFAULT,
            DeletionScope.ACTIVE -> mandatory += PredicateExpression(
                LogicalField("deleted"),
                PredicateOperator.IS_FALSE
            )

            DeletionScope.DELETED -> {
                requireDeletionPermission(authority)
                mandatory += PredicateExpression(LogicalField("deleted"), PredicateOperator.IS_TRUE)
            }

            DeletionScope.ALL -> requireDeletionPermission(authority)
        }
    }

    private fun scopeEquals(field: String, requested: String?, trusted: String?): QueryExpression {
        if (requested != null && requested != trusted) deny()
        return trusted?.let { equals(field, it) } ?: MatchAll
    }

    private fun equals(field: String, value: String): QueryExpression = PredicateExpression(
        LogicalField(field),
        PredicateOperator.EQ,
        listOf(JsonNodeFactory.instance.stringNode(value))
    )

    private fun requireDeletionPermission(authority: QueryAuthority) {
        if (QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS !in authority.permissions) deny()
    }

    private fun deny(): Nothing = throw QueryPolicyDeniedException()
}

internal class CompositeQueryPolicy(policies: List<QueryPolicy>) : QueryPolicy {
    private val policies = policies.toList()

    init {
        require(this.policies.isNotEmpty()) { "At least one query policy is required." }
    }

    override fun evaluate(context: QueryPolicyContext): Mono<QueryAuthorization> = Flux.fromIterable(policies)
        .concatMap { policy -> policy.evaluate(context).switchIfEmpty(Mono.error(QueryPolicyFailureException())) }
        .reduce(QueryAuthorization(), ::merge)
        .map { authorization -> validate(authorization, context.query.filter) }
        .onErrorMap(::mapError)

    private fun validate(
        authorization: QueryAuthorization,
        requestedFilter: QueryExpression
    ): QueryAuthorization {
        if (authorization.decision != QueryDecision.GRANT) throw QueryPolicyDeniedException()
        requestedCapabilities(and(listOf(requestedFilter, authorization.mandatoryFilter))).forEach { capability ->
            if (authorization.capabilities[capability] != CapabilityDecision.GRANT) throw QueryPolicyDeniedException()
        }
        return authorization
    }

    private fun merge(left: QueryAuthorization, right: QueryAuthorization): QueryAuthorization = QueryAuthorization(
        decision = merge(left.decision, right.decision),
        mandatoryFilter = and(listOf(left.mandatoryFilter, right.mandatoryFilter)),
        fieldAccess = intersect(left.fieldAccess, right.fieldAccess),
        maximumBudget = min(left.maximumBudget, right.maximumBudget),
        capabilities = mergeCapabilities(left.capabilities, right.capabilities)
    )

    private fun merge(left: QueryDecision, right: QueryDecision): QueryDecision = when {
        left == QueryDecision.DENY || right == QueryDecision.DENY -> QueryDecision.DENY
        left == QueryDecision.GRANT || right == QueryDecision.GRANT -> QueryDecision.GRANT
        else -> QueryDecision.ABSTAIN
    }

    private fun intersect(left: QueryFieldAccess, right: QueryFieldAccess): QueryFieldAccess = when {
        left is QueryFieldAccess.Unrestricted -> right
        right is QueryFieldAccess.Unrestricted -> left
        left is QueryFieldAccess.Restricted && right is QueryFieldAccess.Restricted ->
            QueryFieldAccess.Restricted(left.fields intersect right.fields)

        else -> error("Unknown query field access.")
    }

    private fun min(left: QueryBudget, right: QueryBudget): QueryBudget = QueryBudget(
        timeout = minOfNullable(left.timeout, right.timeout),
        maxRecords = minOfNullable(left.maxRecords, right.maxRecords)
    )

    private fun mergeCapabilities(
        left: Map<QueryCapabilityId, CapabilityDecision>,
        right: Map<QueryCapabilityId, CapabilityDecision>
    ): Map<QueryCapabilityId, CapabilityDecision> = (left.keys + right.keys).associateWith { capability ->
        val decisions =
            setOf(left[capability] ?: CapabilityDecision.ABSTAIN, right[capability] ?: CapabilityDecision.ABSTAIN)
        when {
            CapabilityDecision.DENY in decisions -> CapabilityDecision.DENY
            CapabilityDecision.GRANT in decisions -> CapabilityDecision.GRANT
            else -> CapabilityDecision.ABSTAIN
        }
    }

    private fun mapError(error: Throwable): Throwable {
        Exceptions.throwIfFatal(error)
        return when (error) {
            is QueryException -> error
            is QueryPolicyDeniedException -> QueryException(QueryErrorCode.POLICY_DENIED, QueryStage.POLICY)
            else -> QueryException(QueryErrorCode.POLICY_FAILURE, QueryStage.POLICY)
        }
    }
}

private fun and(expressions: Collection<QueryExpression>): QueryExpression {
    val operands = expressions.flatMap { expression ->
        when {
            expression === MatchAll -> emptyList()
            expression is LogicalExpression && expression.operator == LogicalOperator.AND -> expression.operands
            else -> listOf(expression)
        }
    }
    return when (operands.size) {
        0 -> MatchAll
        1 -> operands.single()
        else -> LogicalExpression(LogicalOperator.AND, operands)
    }
}

internal fun secureFilter(requested: QueryExpression, mandatory: QueryExpression): QueryExpression =
    and(listOf(requested, mandatory))

internal fun requestedCapabilities(expression: QueryExpression): Set<QueryCapabilityId> {
    val result = linkedSetOf<QueryCapabilityId>()
    val pending = ArrayDeque<QueryExpression>()
    pending += expression
    while (pending.isNotEmpty()) {
        when (val current = pending.removeLast()) {
            is me.ahoo.wow.api.query.SearchExpression -> result += QueryCapabilities.FULL_TEXT
            is LegacyConditionExpression -> result += QueryCapabilities.LEGACY_BACKEND
            is LogicalExpression -> pending.addAll(current.operands)
            is me.ahoo.wow.api.query.ElementMatchExpression -> pending += current.predicate
            else -> Unit
        }
    }
    return result
}

private fun <T : Comparable<T>> minOfNullable(left: T?, right: T?): T? = when {
    left == null -> right
    right == null -> left
    else -> minOf(left, right)
}

private class QueryPolicyDeniedException : RuntimeException(null, null, false, false)

private class QueryPolicyFailureException : RuntimeException(null, null, false, false)
