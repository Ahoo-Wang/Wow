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

package me.ahoo.wow.tck.query

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.modeling.annotation.aggregateMetadata

val QUERY_OPERATOR_AGGREGATE_METADATA =
    aggregateMetadata<QueryOperatorCommandAggregate, QueryOperatorState>()

@AggregateRoot
@AggregateRoute(enabled = false)
class QueryOperatorCommandAggregate(val state: QueryOperatorState)

data class QueryOperatorItem(
    val sku: String,
    val quantity: Int
)

data class QueryOperatorState(val id: String) {
    var name: String = ""
        internal set
    var score: Int = 0
        internal set
    var labels: List<String> = emptyList()
        internal set
    var numbers: List<Int> = emptyList()
        internal set
    var active: Boolean = false
        internal set
    var items: List<QueryOperatorItem> = emptyList()
        internal set
}
