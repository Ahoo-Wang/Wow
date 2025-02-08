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

package me.ahoo.wow.query.dsl

import me.ahoo.wow.api.query.Condition
import kotlin.reflect.KCallable

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
 *     "field100" between 1 to 2
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
class ConditionDsl : NestedFieldDsl() {

    private var conditions: MutableList<Condition> = mutableListOf()

    fun condition(condition: Condition) {
        conditions.add(condition)
    }

    infix fun String.nested(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.nested(this.withNestedField())
        nestedDsl.block()
        nestedDsl.conditions.forEach {
            condition(it)
        }
    }

    infix fun KCallable<*>.nested(block: ConditionDsl.() -> Unit) {
        name.nested(block)
    }

    fun and(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.nested(nestedField)
        nestedDsl.block()
        if (nestedDsl.conditions.isEmpty()) {
            return
        }
        val nestedCondition = Condition.and(nestedDsl.conditions)
        condition(nestedCondition)
    }

    fun or(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.nested(nestedField)
        nestedDsl.block()
        if (nestedDsl.conditions.isEmpty()) {
            return
        }
        val nestedCondition = Condition.or(nestedDsl.conditions)
        condition(nestedCondition)
    }

    fun nor(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.nested(nestedField)
        nestedDsl.block()
        if (nestedDsl.conditions.isEmpty()) {
            return
        }
        val nestedCondition = Condition.nor(nestedDsl.conditions)
        condition(nestedCondition)
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
        ids(value.toList())
    }

    fun aggregateId(value: String) {
        condition(Condition.aggregateId(value))
    }

    fun aggregateIds(value: List<String>) {
        condition(Condition.aggregateIds(value))
    }

    fun aggregateIds(vararg value: String) {
        aggregateIds(value.toList())
    }

    fun tenantId(value: String) {
        condition(Condition.tenantId(value))
    }

    fun deleted(value: Boolean) {
        condition(Condition.deleted(value))
    }

    infix fun String.eq(value: Any) {
        condition(Condition.eq(this.withNestedField(), value))
    }

    infix fun KCallable<*>.eq(value: Any) {
        name eq value
    }

    infix fun String.ne(value: Any) {
        condition(Condition.ne(this.withNestedField(), value))
    }

    infix fun KCallable<*>.ne(value: Any) {
        name ne value
    }

    infix fun String.gt(value: Any) {
        condition(Condition.gt(this.withNestedField(), value))
    }

    infix fun KCallable<*>.gt(value: Any) {
        name gt value
    }

    infix fun String.lt(value: Any) {
        condition(Condition.lt(this.withNestedField(), value))
    }

    infix fun KCallable<*>.lt(value: Any) {
        name lt value
    }

    infix fun String.gte(value: Any) {
        condition(Condition.gte(this.withNestedField(), value))
    }

    infix fun KCallable<*>.gte(value: Any) {
        name gte value
    }

    infix fun String.lte(value: Any) {
        condition(Condition.lte(this.withNestedField(), value))
    }

    infix fun KCallable<*>.lte(value: Any) {
        name lte value
    }

    fun String.contains(value: String, ignoreCase: Boolean = false) {
        condition(Condition.contains(this.withNestedField(), value, ignoreCase))
    }

    infix fun String.contains(value: String) {
        this.contains(value, false)
    }

    infix fun KCallable<*>.contains(value: String) {
        name contains value
    }

    infix fun String.isIn(value: List<Any>) {
        condition(Condition.isIn(this.withNestedField(), value))
    }

    infix fun KCallable<*>.isIn(value: List<Any>) {
        name isIn value
    }

    infix fun String.notIn(value: List<Any>) {
        condition(Condition.notIn(this.withNestedField(), value))
    }

    infix fun KCallable<*>.notIn(value: List<Any>) {
        name notIn value
    }

    infix fun <V> String.between(value: Pair<V, V>) {
        condition(Condition.between(this.withNestedField(), value.first, value.second))
    }

    infix fun <V> String.between(start: V): BetweenStart<V> {
        return BetweenStart(this.withNestedField(), start)
    }

    infix fun <V> KCallable<*>.between(start: V): BetweenStart<V> {
        return name between start
    }

    infix fun <V> BetweenStart<V>.to(end: V) {
        condition(Condition.between(field, start, end))
    }

    infix fun String.all(value: List<Any>) {
        condition(Condition.all(this.withNestedField(), value))
    }

    infix fun KCallable<*>.all(value: List<Any>) {
        name all value
    }

    fun String.startsWith(value: String, ignoreCase: Boolean = false) {
        condition(Condition.startsWith(this.withNestedField(), value, ignoreCase))
    }

    infix fun String.startsWith(value: String) {
        this.startsWith(value, false)
    }

    infix fun KCallable<*>.startsWith(value: String) {
        name startsWith value
    }

    fun String.endsWith(value: String, ignoreCase: Boolean = false) {
        condition(Condition.endsWith(this.withNestedField(), value, ignoreCase))
    }

    infix fun String.endsWith(value: String) {
        this.endsWith(value, false)
    }

    infix fun KCallable<*>.endsWith(value: String) {
        name endsWith value
    }

    infix fun String.elemMatch(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.block()
        condition(Condition.elemMatch(this.withNestedField(), nestedDsl.build()))
    }

    infix fun KCallable<*>.elemMatch(block: ConditionDsl.() -> Unit) {
        name elemMatch block
    }

    fun String.isNull() {
        condition(Condition.isNull(this.withNestedField()))
    }

    fun KCallable<*>.isNull() {
        name.isNull()
    }

    fun String.notNull() {
        condition(Condition.notNull(this.withNestedField()))
    }

    fun KCallable<*>.notNull() {
        name.notNull()
    }

    fun String.isTrue() {
        condition(Condition.isTrue(this.withNestedField()))
    }

    fun KCallable<*>.isTrue() {
        name.isTrue()
    }

    fun String.isFalse() {
        condition(Condition.isFalse(this.withNestedField()))
    }

    fun KCallable<*>.isFalse() {
        name.isFalse()
    }

    fun String.today(datePattern: Any? = null) {
        condition(Condition.today(this.withNestedField(), datePattern))
    }

    fun KCallable<*>.today(datePattern: Any? = null) {
        name.today(datePattern)
    }

    infix fun String.beforeToday(time: Any) {
        condition(Condition.beforeToday(this.withNestedField(), time))
    }

    fun String.tomorrow(datePattern: Any? = null) {
        condition(Condition.tomorrow(this.withNestedField(), datePattern))
    }

    fun KCallable<*>.tomorrow(datePattern: Any? = null) {
        name.tomorrow(datePattern)
    }

    fun String.thisWeek(datePattern: Any? = null) {
        condition(Condition.thisWeek(this.withNestedField(), datePattern))
    }

    fun KCallable<*>.thisWeek(datePattern: Any? = null) {
        name.thisWeek(datePattern)
    }

    fun String.nextWeek(datePattern: Any? = null) {
        condition(Condition.nextWeek(this.withNestedField(), datePattern))
    }

    fun KCallable<*>.nextWeek(datePattern: Any? = null) {
        name.nextWeek(datePattern)
    }

    fun String.lastWeek(datePattern: Any? = null) {
        condition(Condition.lastWeek(this.withNestedField(), datePattern))
    }

    fun KCallable<*>.lastWeek(datePattern: Any? = null) {
        name.lastWeek(datePattern)
    }

    fun String.thisMonth(datePattern: Any? = null) {
        condition(Condition.thisMonth(this.withNestedField(), datePattern))
    }

    fun KCallable<*>.thisMonth(datePattern: Any? = null) {
        name.thisMonth(datePattern)
    }

    fun String.lastMonth(datePattern: Any? = null) {
        condition(Condition.lastMonth(this.withNestedField(), datePattern))
    }

    fun KCallable<*>.lastMonth(datePattern: Any? = null) {
        name.lastMonth(datePattern)
    }

    fun String.recentDays(days: Int, datePattern: Any? = null) {
        condition(Condition.recentDays(this.withNestedField(), days, datePattern))
    }

    infix fun String.recentDays(days: Int) {
        this.recentDays(days, null)
    }

    infix fun KCallable<*>.recentDays(days: Int) {
        name.recentDays(days, null)
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
