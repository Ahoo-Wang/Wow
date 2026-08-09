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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.query.internal.model

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery

internal typealias QueryDocumentKind = me.ahoo.wow.query.gateway.QueryDocumentKind
internal typealias QueryTarget = me.ahoo.wow.query.gateway.QueryTarget
internal typealias QueryOperation = me.ahoo.wow.query.gateway.QueryOperation
internal typealias QueryResultShape = me.ahoo.wow.query.backend.QueryResultShape
internal typealias RecordResultShape = me.ahoo.wow.query.backend.RecordResultShape
internal typealias QueryExecutionMode = me.ahoo.wow.query.gateway.QueryExecutionMode
internal typealias QueryValidationMode = me.ahoo.wow.query.gateway.QueryValidationMode

/**
 * Invocation input at the application boundary.
 *
 * Record variants deliberately retain the existing wire DTO so the compatibility adapter can admit and normalize it.
 * They are ephemeral references rather than value objects and must never be cached or used as map keys. The gateway
 * creates them per subscription and P1-B synchronously materializes an immutable admitted snapshot before normalization.
 * AnalyticsWire retains its public wire DTO only until the same per-subscription admission boundary.
 */
internal sealed interface QueryInput {
    class Single(val query: ISingleQuery) : QueryInput

    class Stream(val query: IListQuery) : QueryInput

    class Page(val query: IPagedQuery) : QueryInput

    class Count(val condition: Condition) : QueryInput

    data class Analytics(val query: AnalyticsQuery) : QueryInput

    class AnalyticsWire(val query: me.ahoo.wow.api.query.analytics.AnalyticsQuery) : QueryInput
}

/**
 * Per-subscription envelope describing one query operation.
 *
 * This is intentionally not a value object because legacy record inputs are not deeply immutable. Stable equality,
 * fingerprints and cache keys start at the admitted snapshot, normalized query and plan boundaries.
 */
internal class QueryInvocation(
    val target: QueryTarget,
    val operation: QueryOperation,
    val resultShape: QueryResultShape,
    val input: QueryInput,
) {
    init {
        require(operation.accepts(input)) {
            "Query input does not match operation $operation."
        }
        require(operation.accepts(resultShape)) {
            "Query result shape $resultShape does not match operation $operation."
        }
    }

    private fun QueryOperation.accepts(input: QueryInput): Boolean =
        when (this) {
            QueryOperation.SINGLE -> input is QueryInput.Single
            QueryOperation.STREAM -> input is QueryInput.Stream
            QueryOperation.PAGE -> input is QueryInput.Page
            QueryOperation.COUNT -> input is QueryInput.Count
            QueryOperation.ANALYZE -> input is QueryInput.Analytics || input is QueryInput.AnalyticsWire
        }

    private fun QueryOperation.accepts(resultShape: QueryResultShape): Boolean =
        when (this) {
            QueryOperation.SINGLE,
            QueryOperation.STREAM,
            QueryOperation.PAGE,
            -> resultShape == QueryResultShape.TYPED || resultShape == QueryResultShape.DYNAMIC

            QueryOperation.COUNT -> resultShape == QueryResultShape.COUNT
            QueryOperation.ANALYZE -> resultShape == QueryResultShape.ANALYTICS
        }
}
