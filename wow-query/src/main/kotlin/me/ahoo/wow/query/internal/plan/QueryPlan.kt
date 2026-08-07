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

package me.ahoo.wow.query.internal.plan

import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.RecordResultShape
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.value.NonEmptyList

internal sealed interface QueryPlan {
    val target: QueryTarget
    val operation: QueryOperation
}

internal sealed interface FilteredQueryPlan : QueryPlan {
    val condition: NormalizedCondition
}

internal sealed interface RecordQueryPlan : FilteredQueryPlan {
    val resultShape: RecordResultShape
}

internal data class SingleQueryPlan(
    override val target: QueryTarget,
    override val condition: NormalizedCondition,
    override val resultShape: RecordResultShape,
) : RecordQueryPlan {
    override val operation: QueryOperation = QueryOperation.SINGLE
}

internal sealed interface StreamLimit {
    data object Unbounded : StreamLimit

    data class Bounded(val value: Int) : StreamLimit {
        init {
            require(value > 0) {
                "Bounded stream limit must be positive."
            }
        }
    }
}

internal data class StreamQueryPlan(
    override val target: QueryTarget,
    override val condition: NormalizedCondition,
    override val resultShape: RecordResultShape,
    val limit: StreamLimit,
) : RecordQueryPlan {
    override val operation: QueryOperation = QueryOperation.STREAM
}

internal data class PageWindow(
    val offset: Long,
    val size: Int,
) {
    init {
        require(offset >= 0) {
            "Page offset must not be negative."
        }
        require(size > 0) {
            "Page size must be positive."
        }
    }
}

internal data class PageQueryPlan(
    override val target: QueryTarget,
    override val condition: NormalizedCondition,
    override val resultShape: RecordResultShape,
    val page: PageWindow,
) : RecordQueryPlan {
    override val operation: QueryOperation = QueryOperation.PAGE
}

internal data class CountQueryPlan(
    override val target: QueryTarget,
    override val condition: NormalizedCondition,
) : FilteredQueryPlan {
    override val operation: QueryOperation = QueryOperation.COUNT
}

internal data class AnalyticsQueryPlan(
    override val target: QueryTarget,
    val preFilter: NormalizedCondition,
    val grouping: AnalyticsGrouping,
    val metrics: NonEmptyList<AnalyticsMetric>,
) : QueryPlan {
    override val operation: QueryOperation = QueryOperation.ANALYZE

    init {
        val dimensionAliases =
            when (grouping) {
                AnalyticsGrouping.Global -> emptyList()
                is AnalyticsGrouping.By -> grouping.dimensions.values.map { it.alias }
            }
        val aliases = dimensionAliases + metrics.values.map { it.alias }
        require(aliases.size == aliases.toSet().size) {
            "Analytics aliases must be unique."
        }
    }
}
