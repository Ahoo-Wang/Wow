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

import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableExpression

class QueryPolicyResult @JvmOverloads constructor(
    val mandatoryExpression: PortableExpression = MatchAll,
    val constraints: QueryPolicyConstraints = QueryPolicyConstraints.NONE
) {
    operator fun component1(): PortableExpression = mandatoryExpression

    operator fun component2(): QueryPolicyConstraints = constraints

    fun copy(
        mandatoryExpression: PortableExpression = this.mandatoryExpression,
        constraints: QueryPolicyConstraints = this.constraints
    ): QueryPolicyResult = QueryPolicyResult(mandatoryExpression, constraints)

    override fun equals(other: Any?): Boolean = other is QueryPolicyResult &&
        mandatoryExpression == other.mandatoryExpression && constraints == other.constraints

    override fun hashCode(): Int = 31 * mandatoryExpression.hashCode() + constraints.hashCode()

    override fun toString(): String =
        "QueryPolicyResult(mandatoryExpression=<redacted>, constraints=<redacted>)"
}
