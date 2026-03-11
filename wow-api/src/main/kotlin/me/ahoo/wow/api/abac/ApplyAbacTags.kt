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

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Event
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.command.validation.CommandValidator

/**
 * 应用 ABAC 标签的命令接口。
 *
 * 用于在命令处理过程中动态设置或更新实体的 ABAC 标签。
 * 继承 [AbacTaggable] 提供标签能力，继承 [CommandValidator] 确保标签格式合法。
 *
 * **验证规则：**
 * - 标签键（key）不能为空或空白字符串
 *
 * @see AbacTaggable
 * @see CommandValidator
 */
@Summary("Apply ABAC Tags")
interface ApplyAbacTags :
    AbacTaggable,
    CommandValidator {
    override fun validate() {
        require(!tags.keys.any { it.isBlank() }) {
            "Tags cannot contain blank keys!"
        }
    }
}

/**
 * 应用 ABAC 标签的默认实现。
 *
 * @property tags 标签映射
 */
@CommandRoute(action = "tags", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class DefaultApplyAbacTags(
    override val tags: AbacTags
) : ApplyAbacTags

/**
 * ABAC 标签已应用的事件。
 *
 * 在标签成功应用后发布，表明实体的标签权限已变更。
 * 事件消费者可据此更新权限缓存、触发重新授权等后续操作。
 *
 * @see AbacTaggable
 */
@Event
interface AbacTagsApplied : AbacTaggable

/**
 * ABAC 标签已应用事件的默认实现。
 *
 * @property tags 标签映射
 */
data class DefaultAbacTagsApplied(
    override val tags: AbacTags
) : AbacTagsApplied
