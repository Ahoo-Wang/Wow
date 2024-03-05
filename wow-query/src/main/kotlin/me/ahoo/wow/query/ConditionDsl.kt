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

/**
 * ``` kotlin
 * condition {
 *     "field1" eq "value1"
 *     "field2" ne "value2"
 *     "filed3" gt 1
 *     "field4" lt 1
 *     "field5" gte 1
 *     "field6" lte 1
 *     "field7" contains "value7"
 *     "field8" isIn listOf("value8")
 *     "field9" notIn listOf("value9")
 *     "field10" between (1 to 2)
 *     "field11" all listOf("value11")
 *     "field12" startsWith "value12"
 *     "field13" elemMatch {
 *         "field14" eq "value14"
 *     }
 *     "field15".isNull()
 *     "field16".notNull()
 *     and {
 *         "field3" eq "value3"
 *         "field4" eq "value4"
 *     }
 *     or {
 *         "field3" eq "value3"
 *         "field4" eq "value4"
 *     }
 * }
 * ```
 */
class ConditionDsl {

    private var conditions: MutableList<Condition> = mutableListOf()

    fun condition(condition: Condition) {
        conditions.add(condition)
    }

    fun and(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.block()
        if (nestedDsl.conditions.isEmpty()) {
            return
        }
        val nestedCondition = Condition.and(nestedDsl.conditions)
        condition(nestedCondition)
    }

    fun or(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.block()
        if (nestedDsl.conditions.isEmpty()) {
            return
        }
        val nestedCondition = Condition.or(nestedDsl.conditions)
        condition(nestedCondition)
    }

    fun not(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.block()
        if (nestedDsl.conditions.isEmpty()) {
            return
        }
        val nestedCondition = nestedDsl.build()
        condition(nestedCondition.not())
    }

    fun all() {
        condition(Condition.all())
    }

    fun id(value: String) {
        condition(Condition.id(value))
    }

    fun ids(value: List<String>) {
        condition(Condition.ids(value))
    }

    fun ids(vararg value: String) {
        condition(Condition.ids(value.toList()))
    }

    fun tenantId(value: String) {
        condition(Condition.tenantId(value))
    }

    fun deleted(value: Boolean) {
        condition(Condition.deleted(value))
    }

    infix fun String.eq(value: Any) {
        condition(Condition.eq(this, value))
    }

    infix fun String.ne(value: Any) {
        condition(Condition.ne(this, value))
    }

    infix fun String.gt(value: Any) {
        condition(Condition.gt(this, value))
    }

    infix fun String.lt(value: Any) {
        condition(Condition.lt(this, value))
    }

    infix fun String.gte(value: Any) {
        condition(Condition.gte(this, value))
    }

    infix fun String.lte(value: Any) {
        condition(Condition.lte(this, value))
    }

    infix fun String.contains(value: Any) {
        condition(Condition.contains(this, value))
    }

    infix fun String.isIn(value: List<Any>) {
        condition(Condition.isIn(this, value))
    }

    infix fun String.notIn(value: List<Any>) {
        condition(Condition.notIn(this, value))
    }

    infix fun String.between(value: Pair<Any, Any>) {
        condition(Condition.between(this, value.first, value.second))
    }

    infix fun String.all(value: List<Any>) {
        condition(Condition.all(this, value))
    }

    infix fun String.startsWith(value: Any) {
        condition(Condition.startsWith(this, value))
    }

    infix fun String.endsWith(value: Any) {
        condition(Condition.endsWith(this, value))
    }

    infix fun String.elemMatch(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.block()
        condition(Condition.elemMatch(this, nestedDsl.build()))
    }

    fun String.isNull() {
        condition(Condition.isNull(this))
    }

    fun String.notNull() {
        condition(Condition.notNull(this))
    }

    fun String.isTrue() {
        condition(Condition.isTrue(this))
    }

    fun String.isFalse() {
        condition(Condition.isFalse(this))
    }

    fun String.today() {
        condition(Condition.today(this))
    }

    fun String.tomorrow() {
        condition(Condition.tomorrow(this))
    }

    fun String.thisWeek() {
        condition(Condition.thisWeek(this))
    }

    fun String.nextWeek() {
        condition(Condition.nextWeek(this))
    }

    fun String.lastWeek() {
        condition(Condition.lastWeek(this))
    }

    fun String.thisMonth() {
        condition(Condition.thisMonth(this))
    }

    fun String.lastMonth() {
        condition(Condition.lastMonth(this))
    }

    infix fun String.recentDays(days: Int) {
        condition(Condition.recentDays(this, days))
    }

    infix fun String.recentWeeks(weeks: Int) {
        condition(Condition.recentWeeks(this, weeks))
    }

    infix fun String.recentMonths(months: Int) {
        condition(Condition.recentMonths(this, months))
    }

    fun raw(value: Any) {
        condition(Condition.raw(value))
    }

    fun build(): Condition {
        if (conditions.isEmpty()) {
            return Condition.all()
        }
        if (conditions.size == 1) {
            return conditions.first()
        }
        return Condition.and(conditions)
    }
}
