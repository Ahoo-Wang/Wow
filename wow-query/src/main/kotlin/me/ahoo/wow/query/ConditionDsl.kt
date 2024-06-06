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

    fun not(block: ConditionDsl.() -> Unit) {
        val nestedDsl = ConditionDsl()
        nestedDsl.nested(nestedField)
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
        ids(value.toList())
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

    infix fun String.contains(value: Any) {
        condition(Condition.contains(this.withNestedField(), value))
    }

    infix fun KCallable<*>.contains(value: Any) {
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

    infix fun String.startsWith(value: Any) {
        condition(Condition.startsWith(this.withNestedField(), value))
    }

    infix fun KCallable<*>.startsWith(value: Any) {
        name startsWith value
    }

    infix fun String.endsWith(value: Any) {
        condition(Condition.endsWith(this.withNestedField(), value))
    }

    infix fun KCallable<*>.endsWith(value: Any) {
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

    fun String.today() {
        condition(Condition.today(this.withNestedField()))
    }

    fun KCallable<*>.today() {
        name.today()
    }

    fun String.tomorrow() {
        condition(Condition.tomorrow(this.withNestedField()))
    }

    fun KCallable<*>.tomorrow() {
        name.tomorrow()
    }

    fun String.thisWeek() {
        condition(Condition.thisWeek(this.withNestedField()))
    }

    fun KCallable<*>.thisWeek() {
        name.thisWeek()
    }

    fun String.nextWeek() {
        condition(Condition.nextWeek(this.withNestedField()))
    }

    fun KCallable<*>.nextWeek() {
        name.nextWeek()
    }

    fun String.lastWeek() {
        condition(Condition.lastWeek(this.withNestedField()))
    }

    fun KCallable<*>.lastWeek() {
        name.lastWeek()
    }

    fun String.thisMonth() {
        condition(Condition.thisMonth(this.withNestedField()))
    }

    fun KCallable<*>.thisMonth() {
        name.thisMonth()
    }

    fun String.lastMonth() {
        condition(Condition.lastMonth(this.withNestedField()))
    }

    fun KCallable<*>.lastMonth() {
        name.lastMonth()
    }

    infix fun String.recentDays(days: Int) {
        condition(Condition.recentDays(this.withNestedField(), days))
    }

    infix fun KCallable<*>.recentDays(days: Int) {
        name recentDays days
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
