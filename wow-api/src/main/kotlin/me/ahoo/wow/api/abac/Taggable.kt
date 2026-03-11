/*
 * Copyright 2021-2025 [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

package me.ahoo.wow.api.abac

/**
 * ABAC 标签键。
 * 用于标识标签类型，如 "dept"（部门）、"role"（角色）、"level"（级别）等。
 */
typealias AbacTagKey = String

/**
 * ABAC 标签值列表。
 * 支持一个标签键对应多个值，实现多对多的权限匹配。
 * 例如：用户同时属于 ["eng", "pm"] 两个部门
 */
typealias AbacTagValue = List<String>

/**
 * ABAC 标签集合。
 *
 * 结构为 Map<AbacTagKey, AbacTagValue>，支持一个 key 对应多个 value。
 *
 * **使用示例：**
 * ```
 * // 用户标签：属于工程部和产品部，角色为管理员
 * mapOf(
 *     "dept" to listOf("eng", "pm"),
 *     "role" to listOf("admin")
 * )
 *
 * // 文档标签：仅允许工程部访问
 * mapOf(
 *     "dept" to listOf("eng")
 * )
 *
 * // 公开资源：无标签或空标签表示完全公开
 * emptyMap()
 * ```
 *
 * **空值处理规则：**
 * - 空字符串 key（如 `""`）视为无效标签
 * - 空列表 value（如 `listOf()`）视为无标签
 * - 推荐在构建时过滤这些无效值：
 *   ```
 *   tags.filter { it.key.isNotBlank() && it.value.isNotEmpty() }
 *   ```
 *
 * **通配符：**
 * - `["*"]` 表示匹配该 key 下的所有值
 *   例如：`mapOf("dept" to listOf("*"))` 可访问任何部门的资源
 */
typealias AbacTags = Map<AbacTagKey, AbacTagValue>

/**
 * 可标记 ABAC 标签的接口。
 *
 * ABAC（Attribute-Based Access Control，基于属性的访问控制）通过评估主体（Principal）
 * 和资源（Resource）的属性标签来决定访问权限。本接口用于为这些实体附加标签信息。
 *
 * **使用场景：**
 * - 资源（Resource）：需要被保护的实体，如文档、API 接口、文件、业务数据等
 * - 主体（Principal）：请求访问的实体，如用户、服务账户、角色、租户等
 *
 */
interface AbacTaggable {
    /**
     * 获取 ABAC 标签集合。
     *
     * @return 标签映射，key 为标签键，value 为标签值列表
     */
    val tags: AbacTags
}

/**
 * ABAC 标签提取器。
 *
 * 用于从状态聚合根（StateAggregate）中动态提取标签，而非硬编码。
 * @see AbacTaggable
 */
interface AbacTagsExtractor<in SOURCE : Any> {
    /**
     * 提取 ABAC 标签。
     *
     * @return 标签映射
     */
    fun extract(source: SOURCE): AbacTags
}

val EMPTY_ABAC_TAGS = emptyMap<AbacTagKey, AbacTagValue>()

/**
 * 判断是否为通配符值。
 *
 * 用于权限匹配：`["*"]` 表示该标签键允许匹配任何值。
 * 当 wildcard 为 true 时，在查询条件中生成 [Operator.EXISTS] 操作符。
 *
 * @return 若列表中包含 "*"，则返回 true
 * @see Operator.EXISTS
 */
val AbacTagValue.wildcard: Boolean
    get() = this.contains("*")
