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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.checkExecution
import me.ahoo.wow.webflux.route.acceptsEventStream
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * The HTTP adapter's side of a query: what happens to the request before the gateway and to the response after it.
 * The HTTP budget itself (sizes, filter nodes and values, expensive operators) belongs to the gateway's
 * [entry policy][QueryGateway.entryPolicy], which admission checks; [of] binds this guard to it, so the rows the guard
 * lets through, the list default it applies and the descriptor limits all follow the budget admission enforces.
 *
 * [strictCountFilter] rejects a count request body whose root names neither `op` nor `operator`; off by default, such
 * a body is read as a legacy condition and counts every row.
 */
class HttpQueryGuard(
    private val defaultListSize: Int = DEFAULT_LIST_SIZE,
    private val idleTimeout: Duration = Duration.ofSeconds(10),
    val strictCountFilter: Boolean = false,
) {
    init {
        require(defaultListSize >= 0) { "defaultListSize must be greater than or equal to 0." }
        require(!idleTimeout.isNegative) { "idleTimeout must be greater than or equal to 0." }
    }

    /** This guard under [gateway]'s HTTP budget. */
    fun of(gateway: QueryGateway<*>): Bound = Bound(gateway.entryPolicy.http)

    /** This guard under [budget]. */
    fun of(budget: QueryBudget): Bound = Bound(budget)

    /** The guard of one gateway, under its HTTP [budget]. */
    inner class Bound internal constructor(val budget: QueryBudget) {
        private val maxListSize = budget.maxListSize
        private val maxPageSize = budget.maxPageSize

        /** The list size applied to a list query that sends `limit = 0`, or `null` when none is applied. */
        val effectiveDefaultListSize: Int?
            get() = if (defaultListSize == 0 || maxListSize == 0) null else defaultListSize.coerceAtMost(maxListSize)

        /**
         * Bounds the execution of a single-result query: applies the idle timeout and fails, as a server fault, on a
         * page that exceeds the page limit. [result] runs on subscription, so checks it performs fail the publisher.
         */
        fun <T : Any> mono(result: () -> Mono<T>): Mono<T> {
            val source = Mono.defer(result).doOnNext { value ->
                val size = when (value) {
                    is PagedList<*> -> value.list.size
                    is CursorPage<*> -> value.list.size
                    else -> return@doOnNext
                }
                checkExecution(maxPageSize == 0 || size <= maxPageSize) {
                    "HTTP query returned [$size] rows, exceeding page limit [$maxPageSize]."
                }
            }
            return if (idleTimeout.isZero) source else source.timeout(idleTimeout)
        }

        /**
         * Bounds the execution of a streaming query: applies the idle timeout, fails (a server fault) on more rows
         * than the list limit, and buffers the rows (at most the list limit) unless the client accepts an event stream or
         * the list limit is off, so a late failure still produces an error response. [result] runs on subscription.
         */
        fun <T : Any> flux(request: ServerRequest, result: () -> Flux<T>): Flux<T> {
            val source = Flux.defer(result)
            val timed = if (idleTimeout.isZero) source else source.timeout(idleTimeout)
            val bounded = if (maxListSize == 0) {
                timed
            } else {
                timed.index().map { indexed ->
                    checkExecution(indexed.t1 < maxListSize) { "HTTP query returned more than [$maxListSize] rows." }
                    indexed.t2
                }
            }
            // Buffering holds at most the list cap. With the cap off (0) nothing bounds the buffer, so the rows stream:
            // a late failure then truncates the response instead of turning it into an error body.
            return if (request.acceptsEventStream() || maxListSize == 0) {
                bounded
            } else {
                bounded.collectList().flatMapMany { Flux.fromIterable(it) }
            }
        }

        /**
         * Rewrites an unbounded ([ListQuery.limit] == 0) request-body list query to the server-side
         * default list size, so clients following the published `limit` default are not rejected.
         * Negative limits are kept for the budget to reject. Disabled when either the default list size or
         * the list limit is 0, matching the 0-disables convention: with list caps off, 0 keeps its
         * query-model meaning of unlimited.
         */
        fun applyListDefault(query: ListQuery): ListQuery {
            if (query.limit != 0 || defaultListSize == 0 || maxListSize == 0) {
                return query
            }
            return query.copy(limit = defaultListSize.coerceAtMost(maxListSize))
        }
    }

    companion object {
        const val DEFAULT_LIST_SIZE: Int = 100
    }
}
