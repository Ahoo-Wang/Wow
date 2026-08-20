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

import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.query.validation.QueryBudgetLimit
import java.util.Collections

class QueryPolicyConstraints @JvmOverloads constructor(
    val fieldAccess: QueryFieldAccess = QueryFieldAccess.UNRESTRICTED,
    capabilityAccess: Map<QueryCapabilityId, CapabilityDecision> = emptyMap(),
    val maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED
) {
    val capabilityAccess: Map<QueryCapabilityId, CapabilityDecision> =
        Collections.unmodifiableMap(LinkedHashMap(capabilityAccess))

    operator fun component1(): QueryFieldAccess = fieldAccess

    operator fun component2(): Map<QueryCapabilityId, CapabilityDecision> = capabilityAccess

    operator fun component3(): QueryBudgetLimit = maxBudget

    fun copy(
        fieldAccess: QueryFieldAccess = this.fieldAccess,
        capabilityAccess: Map<QueryCapabilityId, CapabilityDecision> = this.capabilityAccess,
        maxBudget: QueryBudgetLimit = this.maxBudget
    ): QueryPolicyConstraints = QueryPolicyConstraints(fieldAccess, capabilityAccess, maxBudget)

    override fun equals(other: Any?): Boolean = other is QueryPolicyConstraints &&
        fieldAccess == other.fieldAccess && capabilityAccess == other.capabilityAccess && maxBudget == other.maxBudget

    override fun hashCode(): Int = 31 * (31 * fieldAccess.hashCode() + capabilityAccess.hashCode()) + maxBudget.hashCode()

    override fun toString(): String =
        "QueryPolicyConstraints(fieldAccess=<redacted>, capabilityAccess=<redacted>, maxBudget=$maxBudget)"

    companion object {
        @JvmField
        val NONE: QueryPolicyConstraints = QueryPolicyConstraints()
    }
}
