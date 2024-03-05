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

package me.ahoo.wow.api.query

enum class Operator {
    /**
     * 对提供的条件列表执行逻辑与
     */
    AND,

    /**
     * 对提供的条件列表执行逻辑或
     */
    OR,

    /**
     * 匹配`id`字段值等于指定值的所有文档
     */
    ID,

    /**
     * 匹配`id`字段值等于指定值列表中的任何值的所有文档
     */
    IDS,

    /**
     * 匹配所有文档
     */
    ALL,

    /**
     * 匹配字段名称值等于指定值的所有文档
     */
    EQ,

    /**
     * 匹配字段名称值不等于指定值的所有文档
     */
    NE,

    /**
     * 匹配给定字段的值大于指定值的所有文档
     */
    GT,

    /**
     * 匹配给定字段的值小于指定值的所有文档
     */
    LT,

    /**
     * 匹配给定字段的值大于或等于指定值的所有文档
     */
    GTE,

    /**
     * 匹配给定字段的值小于或等于指定值的所有文档
     */
    LTE,

    /**
     * 匹配给定字段的值包含指定值的所有文档
     */
    LIKE,

    /**
     * 匹配字段值等于指定值列表中的任何值的所有文档
     */
    IN,

    /**
     * 匹配字段值不等于任何指定值或不存在的所有文档
     */
    NOT_IN,

    /**
     * 匹配字段值在指定值范围区间的所有文档
     */
    BETWEEN,

    /**
     * 匹配所有文档，其中字段值是包含所有指定值的数组
     */
    ALL_IN,

    /**
     * 匹配字段值以指定字符串开头的文档
     */
    STARTS_WITH,

    /**
     * 匹配字段值以指定字符串结尾的文档
     */
    ENDS_WITH,

    /**
     * 条件与包含数组字段的所有文档相匹配，其中数组中至少有一个成员与给定的条件匹配。
     */
    ELEM_MATCH,

    /**
     * 匹配字段值在指定值为`null`的所有文档
     */
    NULL,

    /**
     * 匹配字段值在指定值不为`null`的所有文档
     */
    NOT_NULL,

    /**
     * 匹配字段值在指定值为`true`的所有文档
     */
    TRUE,

    /**
     * 匹配字段值在指定值为`false`的所有文档
     */
    FALSE
}

data class Condition(
    val field: String,
    val operator: Operator,
    val value: Any = EMPTY_VALUE,
    /**
     * When `operator` is `AND` or `OR`, `children` cannot be empty.
     */
    val children: List<Condition> = emptyList(),
    /**
     * 匹配所有与传入条件不匹配的文档
     */
    val not: Boolean = false,
) {

    fun not(not: Boolean = true): Condition {
        if (this.not == not) {
            return this
        }
        return copy(not = not)
    }

    companion object {
        const val EMPTY_VALUE = ""
        val ALL = Condition(field = EMPTY_VALUE, operator = Operator.ALL, value = EMPTY_VALUE)

        fun and(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.AND, children = conditions.toList())
        fun and(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.AND, children = conditions)
        fun or(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.OR, children = conditions.toList())
        fun or(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.OR, children = conditions)
        fun all() = ALL
        fun eq(field: String, value: Any) = Condition(field, Operator.EQ, value)
        fun ne(field: String, value: Any) = Condition(field, Operator.NE, value)
        fun gt(field: String, value: Any) = Condition(field, Operator.GT, value)
        fun lt(field: String, value: Any) = Condition(field, Operator.LT, value)
        fun gte(field: String, value: Any) = Condition(field, Operator.GTE, value)
        fun lte(field: String, value: Any) = Condition(field, Operator.LTE, value)
        fun like(field: String, value: Any) = Condition(field, Operator.LIKE, value)
        fun isIn(field: String, value: List<Any>) = Condition(field, Operator.IN, value)
        fun notIn(field: String, value: List<Any>) = Condition(field, Operator.NOT_IN, value)
        fun <V> between(field: String, start: V, end: V) = Condition(field, Operator.BETWEEN, listOf(start, end))
        fun all(field: String, value: List<Any>) = Condition(field, Operator.ALL_IN, value)
        fun startsWith(field: String, value: Any) = Condition(field, Operator.STARTS_WITH, value)
        fun endsWith(field: String, value: Any) = Condition(field, Operator.ENDS_WITH, value)
        fun elemMatch(field: String, value: Condition) = Condition(field, Operator.ELEM_MATCH, children = listOf(value))
        fun isNull(field: String) = Condition(field, Operator.NULL)
        fun notNull(field: String) = Condition(field, Operator.NOT_NULL)
        fun isTrue(field: String) = Condition(field, Operator.TRUE)
        fun isFalse(field: String) = Condition(field, Operator.FALSE)
        fun id(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.ID, value = value)
        fun ids(value: List<String>) = Condition(field = EMPTY_VALUE, operator = Operator.IDS, value = value)
        fun ids(vararg value: String) = Condition(field = EMPTY_VALUE, operator = Operator.IDS, value = value.toList())
    }
}

data class Sort(val field: String, val direction: Direction) {
    enum class Direction {
        ASC, DESC
    }
}

data class Pagination(val index: Int, val size: Int) {
    companion object {
        val DEFAULT = Pagination(1, 10)
        fun offset(index: Int, size: Int) = (index - 1) * size
    }

    fun offset() = offset(index, size)
}

data class Projection(val include: List<String> = emptyList(), val exclude: List<String> = emptyList()) {
    companion object {
        val ALL = Projection()
    }

    fun isEmpty() = include.isEmpty() && exclude.isEmpty()
}

interface IQuery {
    val condition: Condition
    val sort: List<Sort>
    val limit: Int
}

data class Query(
    override val condition: Condition,
    override val sort: List<Sort> = emptyList(),
    override val limit: Int = Int.MAX_VALUE
) : IQuery
