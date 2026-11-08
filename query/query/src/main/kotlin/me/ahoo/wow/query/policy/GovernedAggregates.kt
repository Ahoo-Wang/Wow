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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Which of a service's aggregates are read by an access policy of their own ([AccessPolicies.governs]), as the node
 * that has the policies decides. A service publishes it beside the data it writes elsewhere, so that a reader that
 * cannot evaluate the policy, such as the analytics catalog's nodes, refuses those aggregates' rows rather than reading
 * them by the default rule: the Spring Boot host gives the ClickHouse Sink one, which its `wow_catalog` rows record.
 *
 * It is asked when the data is described, after the node has started, never while the node is assembled.
 */
@InternalWowApi
fun interface GovernedAggregates {
    /** Whether [type] is read by an access policy of its own. */
    fun governs(type: AggregateType): Boolean
}
