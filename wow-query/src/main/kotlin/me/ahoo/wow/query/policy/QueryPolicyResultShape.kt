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

import me.ahoo.wow.api.query.gateway.QueryProjection

sealed interface QueryPolicyResultShape {
    class Typed(
        val resultType: Class<*>,
        val projection: QueryProjection = QueryProjection.All
    ) : QueryPolicyResultShape {
        operator fun component1(): Class<*> = resultType

        operator fun component2(): QueryProjection = projection

        fun copy(
            resultType: Class<*> = this.resultType,
            projection: QueryProjection = this.projection
        ): Typed = Typed(resultType, projection)

        override fun equals(other: Any?): Boolean = other is Typed &&
            resultType == other.resultType && projection == other.projection

        override fun hashCode(): Int = 31 * resultType.hashCode() + projection.hashCode()

        override fun toString(): String = "QueryPolicyResultShape.Typed(<redacted>)"
    }

    data object Dynamic : QueryPolicyResultShape

    data object Count : QueryPolicyResultShape
}
