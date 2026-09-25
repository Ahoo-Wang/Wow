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

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.Context
import reactor.util.context.ContextView

private object QueryScopeKey

private object AuthenticatedQueryScopeKey

private object QueryEntryKey

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
        get() = authenticated.appendFilter(declared)

    companion object {
        val NONE = QueryScope()
    }
}

/**
 * Where a query came from. The gateway reads it once, when the query is subscribed, together with the caller's
 * [scope][queryScope].
 */
enum class QueryEntry {
    /** Written by the HTTP adapter for every built-in query route; HTTP budgets and gates apply. */
    HTTP,

    /** Trusted in-process code, including queries issued from inside query extensions. */
    IN_PROCESS,

    /** Nobody said. Treated as [IN_PROCESS] unless explicit entries are required. */
    UNSPECIFIED,
}

/** Appends a [declared][QueryScopeProvenance.DECLARED] [scope]; see [withQueryScope]. */
fun Context.withQueryScope(scope: FilterExpression): Context = withQueryScope(QueryScope(declared = scope))

/** Appends [scope] to the caller's scope; its authenticated half also to [authenticatedQueryScope]. */
fun Context.withQueryScope(scope: QueryScope): Context {
    val scoped = put(QueryScopeKey, queryScope().appendFilter(scope.filter))
    if (scope.authenticated == MatchAllFilter) {
        return scoped
    }
    return scoped.put(AuthenticatedQueryScopeKey, authenticatedQueryScope().appendFilter(scope.authenticated))
}

/** The caller's whole scope, whatever its provenance: what restricts the query. */
fun ContextView.queryScope(): FilterExpression = getOrDefault(QueryScopeKey, MatchAllFilter)!!

/** The [authenticated][QueryScopeProvenance.AUTHENTICATED] part of the caller's scope. */
fun ContextView.authenticatedQueryScope(): FilterExpression = getOrDefault(AuthenticatedQueryScopeKey, MatchAllFilter)!!

fun Context.withQueryEntry(entry: QueryEntry): Context = put(QueryEntryKey, entry)

fun ContextView.queryEntry(): QueryEntry = getOrDefault(QueryEntryKey, QueryEntry.UNSPECIFIED)!!

/**
 * The context for a query issued from inside a query extension, a policy or a cache loader: the inherited caller
 * scope and entry are dropped and the entry is [QueryEntry.IN_PROCESS], so an HTTP caller's budgets and scope do not
 * leak into a nested lookup.
 */
fun Context.forInProcessQuery(): Context =
    delete(QueryScopeKey).delete(AuthenticatedQueryScopeKey).put(QueryEntryKey, QueryEntry.IN_PROCESS)

/** Runs this query as a nested in-process query; see [forInProcessQuery]. */
fun <T : Any> Mono<T>.asInProcessQuery(): Mono<T> = contextWrite { it.forInProcessQuery() }

/** Runs this query as a nested in-process query; see [forInProcessQuery]. */
fun <T : Any> Flux<T>.asInProcessQuery(): Flux<T> = contextWrite { it.forInProcessQuery() }
