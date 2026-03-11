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
 * 合并两个 ABAC 标签集合。
 *
 * 合并规则：对于同一 key，将两个标签的值列表合并（并集）。
 * - 若 key 只存在于一方，则保留该 key 的所有值
 * - 若 key 同时存在于双方，则合并值列表并去重
 *
 * **使用示例：**
 * ```
 * val tags1 = mapOf("dept" to listOf("eng"), "role" to listOf("admin"))
 * val tags2 = mapOf("dept" to listOf("pm"), "team" to listOf("backend"))
 *
 * tags1.merge(tags2)
 * // 结果：{ "dept": ["eng", "pm"], "role": ["admin"], "team": ["backend"] }
 * ```
 *
 * @param other 另一个待合并的标签集合
 * @return 合并后的新标签集合
 * @see AbacTags ABAC 标签集合类型
 */
fun AbacTags.merge(other: AbacTags): AbacTags {
    if (other.isEmpty()) {
        return this
    }
    if (isEmpty()) {
        return other
    }
    return this.keys.union(other.keys).associateWith { key ->
        (this[key].orEmpty() + other[key].orEmpty()).distinct()
    }
}
