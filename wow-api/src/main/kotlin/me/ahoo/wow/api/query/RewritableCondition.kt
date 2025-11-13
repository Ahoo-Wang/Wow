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
 * Interface for query objects that support condition rewriting and modification.
 *
 * This interface provides methods to replace or append conditions to existing queries,
 * enabling fluent condition building and modification.
 *
 * @param Q The type of the query object that implements this interface, enabling method chaining.
 */
interface RewritableCondition<Q : RewritableCondition<Q>> {
    /**
     * Creates a new query with the specified condition.
     *
     * @param newCondition The new condition to use.
     * @return A new query object with the specified condition.
     */
    fun withCondition(newCondition: Condition): Q

    /**
     * Appends a condition to the existing condition using logical AND.
     *
     * @param append The condition to append.
     * @return A new query object with the combined condition.
     */
    fun appendCondition(append: Condition): Q

    /**
     * Appends a tenant ID condition to filter results by tenant.
     *
     * @param tenantId The tenant ID to filter by.
     * @return A new query object with the tenant ID condition appended.
     */
    fun appendTenantId(tenantId: String): Q = appendCondition(Condition.tenantId(tenantId))
}
