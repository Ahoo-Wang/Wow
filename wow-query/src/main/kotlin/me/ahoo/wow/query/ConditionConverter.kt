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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator

interface ConditionConverter<T> {
    @Suppress("CyclomaticComplexMethod")
    fun convert(condition: Condition): T {
        val target = when (condition.operator) {
            Operator.AND -> and(condition)
            Operator.OR -> or(condition)
            Operator.ID -> id(condition)
            Operator.IDS -> ids(condition)
            Operator.TENANT_ID -> tenantId(condition)
            Operator.ALL -> all(condition)
            Operator.EQ -> eq(condition)
            Operator.NE -> ne(condition)
            Operator.GT -> gt(condition)
            Operator.LT -> lt(condition)
            Operator.GTE -> gte(condition)
            Operator.LTE -> lte(condition)
            Operator.CONTAINS -> contains(condition)
            Operator.IN -> isIn(condition)
            Operator.NOT_IN -> notIn(condition)
            Operator.BETWEEN -> between(condition)
            Operator.ALL_IN -> allIn(condition)
            Operator.STARTS_WITH -> startsWith(condition)
            Operator.ENDS_WITH -> endsWith(condition)
            Operator.ELEM_MATCH -> elemMatch(condition)
            Operator.NULL -> isNull(condition)
            Operator.NOT_NULL -> notNull(condition)
            Operator.TRUE -> isTrue(condition)
            Operator.FALSE -> isFalse(condition)
            Operator.DELETED -> deleted(condition)
            Operator.TODAY -> today(condition)
            Operator.TOMORROW -> tomorrow(condition)
            Operator.THIS_WEEK -> thisWeek(condition)
            Operator.NEXT_WEEK -> nextWeek(condition)
            Operator.LAST_WEEK -> lastWeek(condition)
            Operator.THIS_MONTH -> thisMonth(condition)
            Operator.LAST_MONTH -> lastMonth(condition)
            Operator.RECENT_DAYS -> recentDays(condition)
            Operator.RAW -> raw(condition)
        }
        return not(condition.not, target)
    }

    fun and(condition: Condition): T
    fun or(condition: Condition): T
    fun id(condition: Condition): T
    fun ids(condition: Condition): T
    fun tenantId(condition: Condition): T
    fun all(condition: Condition): T
    fun eq(condition: Condition): T
    fun ne(condition: Condition): T
    fun gt(condition: Condition): T
    fun lt(condition: Condition): T
    fun gte(condition: Condition): T
    fun lte(condition: Condition): T
    fun contains(condition: Condition): T
    fun isIn(condition: Condition): T
    fun notIn(condition: Condition): T
    fun between(condition: Condition): T
    fun allIn(condition: Condition): T
    fun startsWith(condition: Condition): T
    fun endsWith(condition: Condition): T
    fun elemMatch(condition: Condition): T
    fun isNull(condition: Condition): T
    fun notNull(condition: Condition): T
    fun isTrue(condition: Condition): T
    fun isFalse(condition: Condition): T
    fun deleted(condition: Condition): T
    fun today(condition: Condition): T
    fun tomorrow(condition: Condition): T
    fun thisWeek(condition: Condition): T
    fun nextWeek(condition: Condition): T
    fun lastWeek(condition: Condition): T
    fun thisMonth(condition: Condition): T
    fun lastMonth(condition: Condition): T
    fun recentDays(condition: Condition): T
    fun raw(condition: Condition): T
    fun not(not: Boolean, target: T): T
}
