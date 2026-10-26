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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter

/** Where a caller's scope came from. Only [AUTHENTICATED] scope is a security boundary. */
internal enum class QueryScopeProvenance {
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

    /** Trusted in-process code, including queries issued from inside query extensions; the in-process budget applies. */
    IN_PROCESS,

    /**
     * Trusted in-process code that dropped the in-process budget (`QueryApi.trusted().unbounded()`): only the
     * in-process timeout applies.
     */
    UNBOUNDED,

    /** Nobody said. Treated as [IN_PROCESS] unless explicit entries are required. */
    UNSPECIFIED,
}

/**
 * Who runs a query, and how (decisions D126, D165): the explicit argument of every gateway operation and of
 * [QueryPolicy], in place of 9.x's Reactor `ContextView`. The identity is the [Caller] commands are admitted with:
 * the HTTP edge resolves one per request and, for a `Wow-Scope` request (decision D129, work package 6.2c), has
 * [QueryTargets] decide the tenant and scope it acts in; the query adds only its execution context. In-process code passes [IN_PROCESS].
 *
 * - [caller]: who; null for trusted in-process code, which is bound to no tenant, owner or space. Policies read it:
 *   [ScopeQueryPolicy] restricts a query to its tenant (through the host's `TenantPolicy`) and its scope, and
 *   [AbacQueryPolicy] matches its `abac.` attributes against the snapshot tags.
 * - [entry]: where the query came from, which picks its budget.
 * - [selection]: what a route names in its URL (an aggregate id, a version range): an operation constraint, not
 *   caller scope. Admission appends it at step 2 with the scope, after every
 *   [QueryFilter][me.ahoo.wow.query.filter.QueryFilter], so a rewrite can neither see nor remove it, and it is neither
 *   budgeted as scope nor reported among the audit's scope fields.
 * - [scope]: the caller's scope as filters (tenant, owner, space) a trusted component stated, which admission step 2
 *   appends to the query with what the policies add.
 * - [crossTenant]: the tenants a cross-tenant query reads in place of the caller's own (decision D254 ⑦); see
 *   [CrossTenant].
 * - [target]: the scope the request named (`Wow-Scope`, decision D95), which [QueryTargets] admitted: the [caller]
 *   then already acts in it.
 *
 * An edge never decides the tenant, the scope or the cross-tenant reach itself: it builds the caller of a request
 * through [QueryTargets.of], the one home of those rules (review R5.C-1). The constructor that states the cross-tenant
 * reach and the target is internal, and the engine checks both again for an [HTTP][QueryEntry.HTTP] caller
 * ([QueryTargets.recheck]), so a caller built otherwise cannot reach further than [QueryTargets] lets it. Other code
 * builds a plain caller with [of], or uses [IN_PROCESS].
 */
class QueryCaller @InternalWowApi constructor(
    val caller: Caller? = null,
    val entry: QueryEntry = QueryEntry.UNSPECIFIED,
    val selection: FilterExpression = MatchAllFilter,
    val scope: QueryScope = QueryScope.NONE,
    val crossTenant: CrossTenant? = null,
    val target: TargetScope? = null,
) {
    /** The [caller]'s subject, for policies and audit; null for an anonymous or in-process caller. */
    val subject: String? get() = caller?.subject

    /** The [caller]'s attributes, such as the `abac.` attributes [AbacQueryPolicy] matches; none in process. */
    val attributes: Map<String, List<String>> get() = caller?.attributes ?: emptyMap()

    /**
     * The tenant the [caller] acts in; null for a query bound to no tenant, such as trusted in-process code, and for a
     * [crossTenant] query, which [ScopeQueryPolicy] restricts to its tenants instead.
     */
    val tenantId: String? get() = if (crossTenant != null) null else caller?.tenantId

    /** The owner and space the [caller] acts in: [ScopeQueryPolicy] restricts its queries to each part present. */
    val principal: Scope get() = caller?.scope ?: Scope.NONE

    /** This caller with [scope] appended to its scope. */
    fun withScope(scope: QueryScope): QueryCaller = copy(scope = this.scope.append(scope))

    /** This caller with a route [selection] appended; see [selection]. */
    fun withSelection(selection: FilterExpression): QueryCaller =
        if (selection === MatchAllFilter) this else copy(selection = this.selection.appendFilter(selection))

    /**
     * The caller of a query issued from inside a query extension, a policy or a cache loader: trusted in-process code,
     * [IN_PROCESS]. The caller, scope, route selection, cross-tenant reach and entry are all dropped, so an HTTP
     * caller's budgets, tenant, scope and tags do not leak into a nested lookup.
     */
    fun inProcess(): QueryCaller = IN_PROCESS

    /** This caller with the parts given replaced. */
    @InternalWowApi
    fun copy(
        caller: Caller? = this.caller,
        entry: QueryEntry = this.entry,
        selection: FilterExpression = this.selection,
        scope: QueryScope = this.scope,
        crossTenant: CrossTenant? = this.crossTenant,
        target: TargetScope? = this.target,
    ): QueryCaller = QueryCaller(caller, entry, selection, scope, crossTenant, target)

    override fun equals(other: Any?): Boolean = this === other ||
        other is QueryCaller && caller == other.caller && entry == other.entry && selection == other.selection &&
        scope == other.scope && crossTenant == other.crossTenant && target == other.target

    override fun hashCode(): Int = listOf(caller, entry, selection, scope, crossTenant, target).hashCode()

    override fun toString(): String =
        "QueryCaller(caller=$caller, entry=$entry, selection=$selection, scope=$scope, crossTenant=$crossTenant, " +
            "target=$target)"

    companion object {
        /** Trusted in-process code with no caller and no scope. */
        @JvmField
        val IN_PROCESS: QueryCaller = QueryCaller(entry = QueryEntry.IN_PROCESS)

        /** A query that states nothing: in-process unless the entry policy requires explicit entries. */
        @JvmField
        val UNSPECIFIED: QueryCaller = QueryCaller()

        /**
         * A plain caller: [caller]'s identity on [entry], in its own tenant and scope, naming no other scope and no
         * other tenant. An edge that lets a caller name them builds it through [QueryTargets.of] instead.
         */
        @JvmStatic
        @JvmOverloads
        fun of(caller: Caller?, entry: QueryEntry = QueryEntry.UNSPECIFIED): QueryCaller = QueryCaller(caller, entry)
    }
}
