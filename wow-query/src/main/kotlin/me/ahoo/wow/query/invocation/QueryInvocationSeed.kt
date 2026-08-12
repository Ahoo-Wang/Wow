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

package me.ahoo.wow.query.invocation

import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.validation.QueryBudgetLimit
import java.time.Instant
import java.time.ZoneId

internal class QueryInvocationSeed(
    val request: QueryRequest,
    val operation: QueryOperation,
    val entryProvenance: QueryProvenance,
    val scope: QueryInvocationScope,
    val frozenInstant: Instant,
    val zoneId: ZoneId,
    val admissionDeadline: Instant?,
    val admissionBudget: QueryBudgetLimit
) {
    fun toInvocation(
        schema: QuerySchemaView,
        normalizedExpression: QueryExpression
    ): QueryInvocation = QueryInvocation(
        request = request,
        operation = operation,
        scope = scope,
        frozenInstant = frozenInstant,
        zoneId = zoneId,
        admissionDeadline = admissionDeadline,
        admissionBudget = admissionBudget,
        schema = schema,
        normalizedExpression = normalizedExpression,
        expressionProvenance = mapOf(entryProvenance to normalizedExpression)
    )
}
