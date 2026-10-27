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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.error.QueryScopeRequiredException
import me.ahoo.wow.query.resolve.isNullValue
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.requireValid
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.query.spec.ValueArity
import me.ahoo.wow.query.spec.spec
import kotlin.time.Duration

/**
 * What a gateway requires of a query's [QueryEntry] before admitting it, and the budget each entry runs under.
 *
 * @property requireExplicitEntry rejects [QueryEntry.UNSPECIFIED] queries, so every caller must say whether it is
 * HTTP or in-process. Off by default: an unspecified entry is treated as in-process, which keeps existing
 * QueryGateway callers working unchanged.
 * @property requireAuthenticatedScope rejects an [QueryEntry.HTTP] query on a built-in model whose
 * [authenticated scope][QueryScope.authenticated] does not pin the model's
 * [required scope][me.ahoo.wow.query.schema.QueryModelProfile.requiredScope]. A declared scope (a tenant header,
 * say) still filters the query but never satisfies this check. Off by default: the declared scope is trusted.
 * @property http the budget of [QueryEntry.HTTP] queries.
 * @property inProcess the budget of [QueryEntry.IN_PROCESS] and [QueryEntry.UNSPECIFIED] queries; `null`, the
 * default, checks nothing. A host gives it [QueryBudget.IN_PROCESS_DEFAULT] by default (`wow.query.budgets.in-process.*`),
 * so in-process code is bounded as a request is.
 * @property inProcessTimeout the execution timeout of a query whose entry has no budget ([QueryEntry.UNBOUNDED], or
 * every in-process query when [inProcess] is `null`: every query has one); `null` runs such a query
 * without one.
 */
class QueryEntryPolicy @InternalWowApi constructor(
    val requireExplicitEntry: Boolean = false,
    val requireAuthenticatedScope: Boolean = false,
    val http: QueryBudget = QueryBudget.HTTP_DEFAULT,
    val inProcess: QueryBudget? = null,
    val inProcessTimeout: Duration? = QueryBudget.DEFAULT_TIMEOUT,
) {
    init {
        require(inProcessTimeout == null || inProcessTimeout.isPositive()) { "inProcessTimeout must be positive." }
    }

    fun budget(entry: QueryEntry): QueryBudget? = when (entry) {
        QueryEntry.HTTP -> http
        QueryEntry.IN_PROCESS, QueryEntry.UNSPECIFIED -> inProcess
        QueryEntry.UNBOUNDED -> null
    }

    /** The execution timeout of [entry]'s queries: its budget's, or [inProcessTimeout] for an entry without one. */
    fun timeout(entry: QueryEntry): Duration? {
        val budget = budget(entry) ?: return inProcessTimeout
        return budget.timeout.takeUnless { it == Duration.ZERO }
    }

    /** The entry the query runs under, after this policy accepted it. */
    fun admit(entry: QueryEntry): QueryEntry {
        requireValid(
            !(requireExplicitEntry && entry == QueryEntry.UNSPECIFIED)
        ) { QueryViolation.ExplicitEntryRequired }
        return entry
    }

    /** Rejects the query when [requireAuthenticatedScope] applies and [authenticated] misses the required scope. */
    internal fun requireScope(entry: QueryEntry, authenticated: FilterExpression, profile: QueryModelProfile?) {
        if (!requireAuthenticatedScope || entry != QueryEntry.HTTP || profile == null) {
            return
        }
        val required = profile.requiredScope
        if (!authenticated.pins(required, profile)) {
            throw QueryScopeRequiredException(profile.model, required)
        }
    }

    companion object {
        val DEFAULT = QueryEntryPolicy()

        /** A [QueryEntryPolicy] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            requireExplicitEntry: Boolean = false,
            requireAuthenticatedScope: Boolean = false,
            http: QueryBudget = QueryBudget.HTTP_DEFAULT,
            inProcess: QueryBudget? = null,
            inProcessTimeout: Duration? = QueryBudget.DEFAULT_TIMEOUT,
        ): QueryEntryPolicy = QueryEntryPolicy(
            requireExplicitEntry,
            requireAuthenticatedScope,
            http,
            inProcess,
            inProcessTimeout
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryEntryPolicy && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "QueryEntryPolicy(requireExplicitEntry=$requireExplicitEntry, " +
            "requireAuthenticatedScope=$requireAuthenticatedScope, http=$http, inProcess=$inProcess, " +
            "inProcessTimeout=$inProcessTimeout)"

    private fun valueFields(): List<Any?> = listOf(
        requireExplicitEntry,
        requireAuthenticatedScope,
        http,
        inProcess,
        inProcessTimeout
    )
}

/**
 * Whether this scope restricts [field] to one value: at the top level, a single-valued filter on the system field
 * [profile] maps to [field], or an equality.
 */
private fun FilterExpression.pins(field: QueryField, profile: QueryModelProfile): Boolean = when (this) {
    is AndFilter -> operands.any { it.pins(field, profile) }
    is EqualFilter -> this.field == field && !value.isNullValue
    // A model with a fixed tenant has no tenant field: its tenant filter pins the tenant itself.
    is TenantIdFilter -> profile.fixedTenantId != null || profile.systemField(SystemField.TENANT_ID) == field
    else -> spec.arity.values == ValueArity.ONE && spec.systemField?.let(profile::systemField) == field
}
