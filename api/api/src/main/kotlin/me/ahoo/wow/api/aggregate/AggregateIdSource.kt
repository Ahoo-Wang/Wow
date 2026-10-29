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

package me.ahoo.wow.api.aggregate

/**
 * Where an aggregate's id comes from, as `@AggregateRoot(id)` declares it.
 */
enum class AggregateIdSource {
    /**
     * The id the request names, or, for a command that creates its aggregate and names none, one admission
     * generates. The default.
     */
    ASSIGNED,

    /**
     * The id is its owner's: each owner has one such aggregate in its tenant, such as a buyer's basket. An
     * owner-level caller addresses it as "mine", by no id, and admission takes the caller's owner for it; naming
     * another id is forbidden. A caller with no owner (a tenant-level caller, a service, a saga's command) names the
     * id. No id is ever generated, the aggregate records its id as its owner, and its owner cannot be transferred.
     * It belongs to its owner ([Ownership.OWNER]).
     */
    OWNER,
}
