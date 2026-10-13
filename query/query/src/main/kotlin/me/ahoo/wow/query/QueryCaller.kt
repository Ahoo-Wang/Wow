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

package me.ahoo.wow.query

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter

/** Where a caller's scope came from. Only [AUTHENTICATED] scope is a security boundary. */
enum class QueryScopeProvenance {
    /** Taken from the caller's credentials, or checked against them by a trusted component. */
    AUTHENTICATED,

    /** Stated by the request itself (a path variable or a header): a filter, not a boundary. */
    DECLARED,
}

/**
 * The caller's scope, split by [provenance][QueryScopeProvenance]. Both halves restrict the query; only the
 * [authenticated] half counts as provided when a gateway requires an authenticated scope.
 */
data class QueryScope(
    val authenticated: FilterExpression = MatchAllFilter,
    val declared: FilterExpression = MatchAllFilter,
) {
    /** The whole scope, as it restricts the query. */
    val filter: FilterExpression
        get() = authenticated.and(declared)

    /** This scope with [scope] appended, half by half. */
    fun append(scope: QueryScope): QueryScope =
        QueryScope(authenticated.and(scope.authenticated), declared.and(scope.declared))

    companion object {
        val NONE = QueryScope()
    }
}

/** This filter AND [other]; a match-all half adds no node, so it is neither budgeted nor seen by policies. */
private fun FilterExpression.and(other: FilterExpression): FilterExpression =
    if (other === MatchAllFilter) this else appendFilter(other)

/** Where a query came from; its [QueryCaller] states it. */
enum class QueryEntry {
    /** Stated by the HTTP edge for every query route; HTTP budgets and gates apply. */
    HTTP,

    /** Trusted in-process code, including queries issued from inside query extensions. */
    IN_PROCESS,

    /** Nobody said. Treated as [IN_PROCESS] unless explicit entries are required. */
    UNSPECIFIED,
}

/**
 * Who runs a query (decision D126): the explicit argument of every gateway operation and of [QueryPolicy], in place
 * of 9.x's Reactor `ContextView`. The HTTP edge maps its `Caller` (and `Wow-Scope` for a cross-scope caller) to one
 * (decision D129, work package 6.2c): [tenantId] is the caller's tenant (or the one `Wow-Scope` names), [principal] its
 * scope (or the target's), [subject] and [attributes] unchanged, and the entry [QueryEntry.HTTP]. In-process code
 * passes [IN_PROCESS].
 *
 * - [entry]: where the query came from, which picks its budget.
 * - [scope]: the caller's scope as filters (tenant, owner, space), which admission step 2 appends to the query.
 * - [selection]: what a route names in its URL (an aggregate id, a version range): an operation constraint, not
 *   caller scope. Admission appends it at step 2 with the scope, after every
 *   [QueryFilter][me.ahoo.wow.query.filter.QueryFilter], so a rewrite can neither see nor remove it, and it is neither
 *   budgeted as scope nor reported among the audit's scope fields.
 * - [subject] names the principal, for policies and audit; `null` for an anonymous or in-process caller.
 * - [attributes] are further facts about the principal a policy may read, such as the `abac.` attributes the ABAC
 *   policy matches against snapshot tags ([AbacQueryPolicy]).
 * - [tenantId] is the tenant the caller acts in, which [ScopeQueryPolicy] restricts its queries to (through the host's
 *   [QueryTenantPolicy]); `null` for a caller bound to no tenant, such as trusted in-process code.
 * - [principal] is the owner and space the caller acts in: [ScopeQueryPolicy] restricts its queries to each part that
 *   is present.
 */
data class QueryCaller(
    val entry: QueryEntry = QueryEntry.UNSPECIFIED,
    val scope: QueryScope = QueryScope.NONE,
    val selection: FilterExpression = MatchAllFilter,
    val subject: String? = null,
    val attributes: Map<String, List<String>> = emptyMap(),
    val tenantId: String? = null,
    val principal: Scope = Scope.NONE,
) {
    init {
        require(tenantId == null || tenantId.isNotBlank()) { "A query caller's tenant, when present, is not blank." }
    }

    /** This caller with [scope] appended to its scope. */
    fun withScope(scope: QueryScope): QueryCaller = copy(scope = this.scope.append(scope))

    /** This caller with a route [selection] appended; see [selection]. */
    fun withSelection(selection: FilterExpression): QueryCaller =
        if (selection === MatchAllFilter) this else copy(selection = this.selection.appendFilter(selection))

    /**
     * The caller of a query issued from inside a query extension, a policy or a cache loader: the scope, route
     * selection, tenant, principal scope and entry are dropped and the entry is [QueryEntry.IN_PROCESS], so an HTTP
     * caller's budgets and scope do not leak into a nested lookup. The subject and attributes stay, for audit.
     */
    fun inProcess(): QueryCaller = QueryCaller(QueryEntry.IN_PROCESS, subject = subject, attributes = attributes)

    companion object {
        /** Trusted in-process code with no scope. */
        @JvmField
        val IN_PROCESS: QueryCaller = QueryCaller(QueryEntry.IN_PROCESS)

        /** A caller that states nothing: in-process unless the entry policy requires explicit entries. */
        @JvmField
        val UNSPECIFIED: QueryCaller = QueryCaller()
    }
}
