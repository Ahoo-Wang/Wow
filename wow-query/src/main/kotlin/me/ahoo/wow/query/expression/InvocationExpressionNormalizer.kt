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

package me.ahoo.wow.query.expression

import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import java.time.Instant
import java.time.ZoneId

internal object InvocationExpressionNormalizer {
    @JvmSynthetic
    fun normalize(
        expression: QueryExpression,
        frozenInstant: Instant,
        zoneId: ZoneId
    ): QueryExpression = when (expression) {
        is LogicalExpression -> ExpressionNormalizer.logical(
            expression.operator,
            expression.operands.map { normalize(it, frozenInstant, zoneId) }
        )

        is PortableLogicalExpression -> ExpressionNormalizer.logical(
            expression.operator,
            expression.operands.map { normalize(it, frozenInstant, zoneId) }
        )

        is ElementMatchExpression -> ElementMatchExpression(
            expression.field,
            normalize(expression.predicate, frozenInstant, zoneId) as PortableExpression
        )

        is RelativeTimeExpression -> RelativeTimeExpressionNormalizer.lower(expression, frozenInstant, zoneId)
        else -> expression
    }
}
