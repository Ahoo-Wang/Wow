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

package me.ahoo.wow.spi.log

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The aggregate a creation command's request claims ([EventLog.claimCreation]): its [aggregateId], the id every
 * attempt of the request takes, and the [scope] of the attempt that claimed it, which admission compares with a later
 * caller's before it hands that caller the id.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class CreationClaim @InternalWowApi constructor(
    val aggregateId: String,
    val scope: Scope,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CreationClaim && aggregateId == other.aggregateId && scope == other.scope

    override fun hashCode(): Int = 31 * aggregateId.hashCode() + scope.hashCode()

    override fun toString(): String = "CreationClaim(aggregateId=$aggregateId, scope=$scope)"

    companion object {
        /** A CreationClaim of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(aggregateId: String, scope: Scope): CreationClaim = CreationClaim(aggregateId, scope)
    }
}
