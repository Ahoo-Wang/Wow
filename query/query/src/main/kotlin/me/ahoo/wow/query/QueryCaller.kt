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
import me.ahoo.wow.api.caller.Caller
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
 * Who runs a query, and how (decisions D126, D165): the explicit argument of every gateway operation and of
 * [QueryPolicy], in place of 9.x's Reactor `ContextView`. The identity is the [Caller] commands are admitted with:
 * the HTTP edge resolves one per request and, for a `Wow-Scope` request (decision D129, work package 6.2c), states the
 * tenant and scope it acts in on it; the query adds only its execution context. In-process code passes [IN_PROCESS].
 *
 * - [caller]: who; null for trusted in-process code, which is bound to no tenant, owner or space. Policies read it:
 *   [ScopeQueryPolicy] restricts a query to its tenant (through the host's [QueryTenantPolicy]) and its scope, and
 *   [AbacQueryPolicy] matches its `abac.` attributes against the snapshot tags.
 * - [entry]: where the query came from, which picks its budget.
 * - [selection]: what a route names in its URL (an aggregate id, a version range): an operation constraint, not
 *   caller scope. Admission appends it at step 2 with the scope, after every
 *   [QueryFilter][me.ahoo.wow.query.filter.QueryFilter], so a rewrite can neither see nor remove it, and it is neither
 *   budgeted as scope nor reported among the audit's scope fields.
 * - [scope]: the caller's scope as filters (tenant, owner, space) a trusted component stated, which admission step 2
 *   appends to the query with what the policies add.
 */
data class QueryCaller(
    val caller: Caller? = null,
    val entry: QueryEntry = QueryEntry.UNSPECIFIED,
    val selection: FilterExpression = MatchAllFilter,
    val scope: QueryScope = QueryScope.NONE,
) {
    /** The [caller]'s subject, for policies and audit; null for an anonymous or in-process caller. */
    val subject: String? get() = caller?.subject

    /** The [caller]'s attributes, such as the `abac.` attributes [AbacQueryPolicy] matches; none in process. */
    val attributes: Map<String, List<String>> get() = caller?.attributes ?: emptyMap()

    /** The tenant the [caller] acts in; null for a query bound to no tenant, such as trusted in-process code. */
    val tenantId: String? get() = caller?.tenantId

    /** The owner and space the [caller] acts in: [ScopeQueryPolicy] restricts its queries to each part present. */
    val principal: Scope get() = caller?.scope ?: Scope.NONE

    /** This caller with [scope] appended to its scope. */
    fun withScope(scope: QueryScope): QueryCaller = copy(scope = this.scope.append(scope))

    /** This caller with a route [selection] appended; see [selection]. */
    fun withSelection(selection: FilterExpression): QueryCaller =
        if (selection === MatchAllFilter) this else copy(selection = this.selection.appendFilter(selection))

    /**
     * The caller of a query issued from inside a query extension, a policy or a cache loader: trusted in-process code,
     * [IN_PROCESS]. The caller, scope, route selection and entry are all dropped, so an HTTP caller's budgets, tenant,
     * scope and tags do not leak into a nested lookup.
     */
    fun inProcess(): QueryCaller = IN_PROCESS

    companion object {
        /** Trusted in-process code with no caller and no scope. */
        @JvmField
        val IN_PROCESS: QueryCaller = QueryCaller(entry = QueryEntry.IN_PROCESS)

        /** A query that states nothing: in-process unless the entry policy requires explicit entries. */
        @JvmField
        val UNSPECIFIED: QueryCaller = QueryCaller()
    }
}
