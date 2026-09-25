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
import me.ahoo.wow.api.query.schema.QueryCapability

/**
 * The single specification of each aggregation group type (design §6.1): its wire name (the enum name, equal to the
 * JSON `type`) and the capability its field must grant. Admission, field resolution and the descriptor read it, so a
 * new group type is added here and every exhaustive `when` over [AggregationGroup] fails to compile until handled.
 */
enum class GroupSpec(val capability: QueryCapability) {
    TERMS(QueryCapability.AGGREGATE_TERMS),
    HISTOGRAM(QueryCapability.AGGREGATE_NUMERIC),
    DATE_HISTOGRAM(QueryCapability.AGGREGATE_TEMPORAL),

    /** A calendar part (weekday, hour, …) of the field's instant; needs the same temporal capability as histograms. */
    DATE_PART(QueryCapability.AGGREGATE_TEMPORAL),
    ;

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
