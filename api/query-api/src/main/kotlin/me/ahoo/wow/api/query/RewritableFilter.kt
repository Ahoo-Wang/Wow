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

package me.ahoo.wow.api.query

/**
 * A query, or a filter, whose condition can be replaced ([withFilter]) or narrowed ([appendFilter]), each a new one.
 *
 * Sealed: the queries of this package implement it, a user's class does not. Cases may be added in a minor release,
 * so a `when` over it keeps an `else` branch.
 *
 * @param Q The type of the query object that implements this interface, enabling method chaining.
 */
sealed interface RewritableFilter<Q : RewritableFilter<Q>> {
    fun withFilter(newFilter: FilterExpression): Q

    fun appendFilter(append: FilterExpression): Q
}
