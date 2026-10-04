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

package me.ahoo.wow.api.annotation

/**
 * The ownership policy of an aggregate, declared with [AggregateOwner]. Since 9.3.0.
 *
 * @property owned whether operations on the aggregate carry an owner.
 */
enum class OwnerPolicy(
    val owned: Boolean
) {
    /**
     * No owner: operations are performed without an owner context. The default.
     */
    NEVER(false),

    /**
     * Every operation carries an owner: the routes take an `owner/{ownerId}` prefix, and loading the state checks it.
     */
    ALWAYS(true),

    /**
     * The owner ID is the aggregate ID: the aggregate instance itself is the ownership boundary, as for a user's own
     * cart.
     */
    AGGREGATE_ID(true)
}
