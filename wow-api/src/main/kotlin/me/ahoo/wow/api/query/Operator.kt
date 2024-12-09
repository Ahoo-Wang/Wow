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
     * 对提供的条件列表执行逻辑或非
     */
    NOR,

    /**
     * 匹配`id`字段值等于指定值的所有文档
     */
    ID,

    /**
     * 匹配`id`字段值等于指定值列表中的任何值的所有文档
     */
    IDS,

    /**
     * 匹配`tenantId`字段值等于指定值的所有文档
     */
    TENANT_ID,

    /**
     * 匹配`deleted`字段值等于指定值的所有文档
     */
    DELETED,

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
    CONTAINS,

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
    FALSE,

    /**
     * 原始操作符，将条件值直接作为原始的数据库查询条件
     */
    RAW,

    // #region 日期类型筛选条件，字段要求:以毫秒为单位的 `long` 类型时间戳
    /**
     * 匹配*数值类型时间戳*字段在今天范围区间的所有文档
     * > 比如：`today` 为 `2024-06-06`，则匹配范围 `2024-06-06 00:00:00.000` ~ `2024-06-06 23:59:59.999` 的所有文档
     *
     */
    TODAY,

    /**
     * 匹配*数值类型时间戳*字段在今天_time_之前范围区间的所有文档
     */
    BEFORE_TODAY,

    /**
     * 匹配*数值类型时间戳*字段在昨天范围区间的所有文档
     * > 比如：`today` 为 `2024-06-06`，则匹配范围 `2024-06-05 00:00:00.000` ~ `2024-06-05 23:59:59.999` 的所有文档
     *
     */
    TOMORROW,

    /**
     * 匹配*数值类型时间戳*字段在本周范围区间的所有文档
     */
    THIS_WEEK,

    /**
     * 匹配*数值类型时间戳*字段在下周范围区间的所有文档
     */
    NEXT_WEEK,

    /**
     * 匹配*数值类型时间戳*字段在上周范围区间的所有文档
     */
    LAST_WEEK,

    /**
     * 匹配*数值类型时间戳*字段在本月范围区间的所有文档
     * > 比如:
     * - `today` : `2024-06-06`
     * - 匹配范围 : `2024-06-01 00:00:00.000` ~ `2024-06-30 23:59:59.999` 的所有文档
     *
     */
    THIS_MONTH,

    /**
     * 匹配*数值类型时间戳*字段在上月范围区间的所有文档
     * > 比如：
     * - `today` : `2024-06-06`
     * - 匹配范围 : `2024-05-01 00:00:00.000` ~ `2024-05-31 23:59:59.999` 的所有文档
     *
     */
    LAST_MONTH,

    /**
     * 匹配*数值类型时间戳*字段在指定值最近天数范围区间的所有文档
     * > 比如：近三天
     * - `today` : `2024-06-06`
     * - 匹配范围 : `2024-06-04 00:00:00.000` ~ `2024-06-06 23:59:59.999` 的所有文档
     * - 即 : 今天、昨天、前天
     */
    RECENT_DAYS,
    // #endregion
}
