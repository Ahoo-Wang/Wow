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

package me.ahoo.wow.api.query.spec

import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.inputExpression
import me.ahoo.wow.api.query.schema.QueryCapability

/**
 * The single specification of each aggregation group type (design §6.1): its wire name (the enum name, equal to the
 * JSON `type`), the capability its field must grant and what it costs. Admission, field resolution, the entry gate
 * and the descriptor read it, so a new group type is added here and every exhaustive `when` over [AggregationGroup]
 * fails to compile until handled.
 */
enum class GroupSpec(
    val capability: QueryCapability,
    /** The cost of the group type in general; [cost] refines it for one group. */
    val baseCost: OperatorCost = OperatorCost.NORMAL,
) {
    TERMS(QueryCapability.AGGREGATE_TERMS),
    HISTOGRAM(QueryCapability.AGGREGATE_NUMERIC),
    DATE_HISTOGRAM(QueryCapability.AGGREGATE_TEMPORAL),

    /**
     * A calendar part (weekday, hour, …) of the field's instant; needs the same temporal capability as histograms.
     * The part is computed per record, so no storage can serve it from an index.
     */
    DATE_PART(QueryCapability.AGGREGATE_TEMPORAL, OperatorCost.EXPENSIVE),
    ;

    /**
     * The cost of [group]: an expression input is computed per record and a dense fill materializes every bucket of
     * the range, so either makes a group expensive; otherwise [baseCost].
     */
    fun cost(group: AggregationGroup): OperatorCost {
        require(of(group) == this) { "Group [${of(group)}] does not match spec [$this]." }
        val dense = when (group) {
            is AggregationGroup.DateHistogram -> group.dense
            is AggregationGroup.DatePart -> group.dense
            is AggregationGroup.Terms, is AggregationGroup.Histogram -> false
        }
        return if (dense || group.inputExpression != null) OperatorCost.EXPENSIVE else baseCost
    }

    companion object {
        @JvmStatic
        fun of(group: AggregationGroup): GroupSpec = when (group) {
            is AggregationGroup.Terms -> TERMS
            is AggregationGroup.Histogram -> HISTOGRAM
            is AggregationGroup.DateHistogram -> DATE_HISTOGRAM
            is AggregationGroup.DatePart -> DATE_PART
        }
    }
}

val AggregationGroup.spec: GroupSpec
    get() = GroupSpec.of(this)
