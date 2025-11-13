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
 * Interface for query objects that can have conditions applied to them.
 *
 * This interface extends [RewritableCondition] and provides a way to access and modify
 * the condition associated with a query. It allows for fluent condition building and modification.
 *
 * @param Q The type of the query object that implements this interface, enabling method chaining.
 */
interface ConditionCapable<Q : ConditionCapable<Q>> : RewritableCondition<Q> {
    /**
     * The condition currently applied to this query.
     */
    val condition: Condition

    /**
     * Appends a condition to the existing condition using logical AND.
     * This method combines the current condition with the appended condition.
     *
     * @param append The condition to append to the current condition.
     * @return A new query object with the combined condition.
     */
    override fun appendCondition(append: Condition): Q = withCondition(this.condition.appendCondition(append))
}
