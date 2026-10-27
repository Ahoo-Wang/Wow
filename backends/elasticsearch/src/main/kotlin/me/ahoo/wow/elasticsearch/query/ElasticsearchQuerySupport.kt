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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.alternativesOrSelf

/*
 * The query engine's rules this backend reads as the engine does: the engine keeps them to itself, so each has its
 * one-line copy here.
 */

/** Throws a [QueryExecutionException] with [message] unless [value] holds. */
internal inline fun checkExecution(value: Boolean, message: () -> String) {
    if (!value) throw QueryExecutionException(message())
}

/** Whether the effective sort orders groups by a metric, which only a complete set of groups can answer. */
internal val AggregationQuery.metricSorted: Boolean
    get() {
        val metrics = metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        return sort.any { it.field.path in metrics }
    }

/** Whether some alternative of this value is an array. */
internal fun QueryValueSchema.hasArrayBranch(): Boolean = alternativesOrSelf().any { it.kind == QueryValueKind.ARRAY }

/**
 * Whether this value can be an element scope: every non-null alternative is an array whose non-null items are all
 * objects, so a predicate can match one element as a whole.
 */
internal fun QueryValueSchema.isElementScope(): Boolean = alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
    .let { branches ->
        branches.isNotEmpty() && branches.all { branch ->
            branch.kind == QueryValueKind.ARRAY && checkNotNull(branch.items).alternativesOrSelf()
                .filter { it.kind != QueryValueKind.NULL }
                .let { items -> items.isNotEmpty() && items.all { it.kind == QueryValueKind.OBJECT } }
        }
    }
