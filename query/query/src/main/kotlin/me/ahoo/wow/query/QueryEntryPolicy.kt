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

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TenantIdFilter
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
 * default, checks nothing. A host gives it [QueryBudget.IN_PROCESS_DEFAULT] by default (`wow.query.budgets.in-process.*`,
 * review R4-Q, Q-3), so in-process code is bounded as a request is.
 * @property inProcessTimeout the execution timeout of a query whose entry has no budget ([QueryEntry.UNBOUNDED], or
 * every in-process query when [inProcess] is `null`; decision D150: every query has one); `null` runs such a query
 * without one.
 */
data class QueryEntryPolicy(
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
    }
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
