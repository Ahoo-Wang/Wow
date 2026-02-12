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

import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import kotlin.reflect.KCallable

/**
 * A DSL for building complex conditions in a fluent and readable manner. This class extends [NestedFieldDsl] to support
 * nested field operations and provides a wide range of methods to construct various types of conditions.
 *
 * Example usage:
 * ```kotlin
 * val condition = ConditionDsl().apply {
 *     "name" eq "John"
 *     "age" gt 30
 *     and {
 *         "status" eq "active"
 *         "role" isIn listOf("admin", "user")
 *     }
 *     or {
 *         "email" contains "@example.com"
 *         "phone" isNull()
 *     }
 * }
 * ```
 *
 * Methods:
 * - `condition(condition: Condition)`: Adds a condition to the list of conditions.
 * - `String.nested(block: ConditionDsl.() -> Unit)`: Creates a nested condition block for the given field.
 * - `KCallable<*>.nested(block: ConditionDsl.() -> Unit)`: Creates a nested condition block for the property represented by the KCallable.
 * - `and(block: ConditionDsl.() -> Unit)`: Combines multiple conditions with a logical AND.
 * - `or(block: ConditionDsl.() -> Unit)`: Combines multiple conditions with a logical OR.
 * - `nor(block: ConditionDsl.() -> Unit)`: Combines multiple conditions with a logical NOR.
 * - `all()`: Adds a condition that matches all documents.
 * - `id(value: String)`: Adds a condition to match a specific ID.
 * - `ids(value: List<String>)` and `ids(vararg value: String)`: Adds a condition to match multiple IDs.
 * - `aggregateId(value: String)`, `aggregateIds(value: List<String>)`, and `aggregateIds(vararg value: String)`: Adds a condition to match aggregate IDs.
 * - `tenantId(value: String)`: Adds a condition to match a specific tenant ID.
 * - `ownerId(value: String)`: Adds a condition to match a specific owner ID.
 * - `deleted(value: DeletionState)`: Adds a condition to match based on the deletion state.
 * - `String.eq(value: Any)` and `KCallable<*>.eq(value: Any)`: Adds an equality condition.
 * - `String.ne(value: Any)` and `KCallable<*>.ne(value: Any)`: Adds a not-equal condition.
 * - `String.gt(value: Any)` and `KCallable<*>.gt(value: Any)`: Adds a greater-than condition.
 * - `String.lt(value: Any)` and `KCallable<*>.lt(value: Any)`: Adds a less-than condition.
 * - `String.gte(value: Any)` and `KCallable<*>.gte(value: Any)`: Adds a greater-than-or-equal condition.
 * - `String.lte(value: Any)` and `KCallable<*>.lte(value: Any)`: Adds a less-than-or-equal condition.
 * - `String.contains(value: String, ignoreCase: Boolean = false)` and `KCallable<*>.contains(value: String)`: Adds a condition to check if the field contains the specified value.
 * - `String.isIn(value: List<Any>)` and `KCallable<*>.isIn(value: List<Any>)`: Adds a condition to check if the field is in the specified list.
 * - `String.notIn(value: List<Any>)` and `KCallable<*>.notIn(value: List<Any>)`: Adds a condition to check if the field is not in the specified list.
 * - `String.between(value: Pair<V, V>)` and `KCallable<*>.between(start: V)`: Adds a condition to check if the field is between two values.
 * - `BetweenStart<V>.to(end: V)`: Completes the between condition with the end value.
 * - `String.all(value: List<Any>)` and `KCallable<*>.all(value: List<Any>)`: Adds a condition to check if the field contains all the specified values.
 * - `String.startsWith(value: String, ignoreCase: Boolean = false)` and `KCallable<*>.startsWith(value: String)`: Adds a condition to check if the field starts with the specified value.
 * - `String.endsWith(value: String, ignoreCase: Boolean = false)` and `KCallable<*>.endsWith(value: String)`: Adds a condition to check if the field ends with the specified value.
 * - `String.elemMatch(block: ConditionDsl.() -> Unit)` and `KCallable<*>.elemMatch(block: ConditionDsl.() -> Unit)`: Adds a condition to match elements in an array.
 * - `String.isNull()` and `KCallable<*>.isNull()`: Adds a condition to check if the field is null.
 * - `String.notNull()` and `KCallable<*>.notNull()`: Adds a condition to check if the field is not null.
 * - `String.isTrue()` and `KCallable<*>.isTrue()`: Adds a condition to check if the field is true.
 * - `String.isFalse()` and `KCallable<*>.isFalse()`: Adds a condition to check if the field is false.
 * - `String.exists(exists: Boolean = true)` and `KCallable<*>.exists(exists: Boolean = true)`: Adds a condition to check if the field exists.
 * - `String.today(datePattern: Any? = null)` and `KCallable<*>.today(datePattern: Any? = null)`: Adds a condition to check if the field is today.
 * - `String.beforeToday(time: Any)`: Adds a condition to check if the field is before today at the specified time.
 * - `String.tomorrow(datePattern: Any? = null)`: Adds a condition to check if the field is tomorrow.
 */
@QueryDslMarker
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

    fun ownerId(value: String) {
        condition(Condition.ownerId(value))
    }

    fun spaceId(value: SpaceId) {
        condition(Condition.spaceId(value))
    }

    @Deprecated(message = "Use deleted(DeletionState) instead.", replaceWith = ReplaceWith("deleted(DeletionState)"))
    fun deleted(value: Boolean) {
        condition(Condition.deleted(value))
    }

    fun deleted(value: DeletionState) {
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

    fun String.exists(exists: Boolean = true) {
        condition(Condition.exists(this.withNestedField(), exists))
    }

    fun KCallable<*>.exists(exists: Boolean = true) {
        name.exists(exists)
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

    fun String.earlierDays(days: Int, datePattern: Any? = null) {
        condition(Condition.earlierDays(this.withNestedField(), days, datePattern))
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
