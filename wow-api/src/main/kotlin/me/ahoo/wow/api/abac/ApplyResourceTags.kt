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

package me.ahoo.wow.api.abac

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary

interface ApplyResourceTags : ApplyAbacTags

interface ResourceTagsApplied : AbacTagsApplied

/**
 * 应用 ABAC 资源标签的默认实现。
 *
 * @property tags 标签映射
 */
@Summary("Apply ABAC Resource Tags")
@CommandRoute(action = "tags", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class DefaultApplyResourceTags(
    override val tags: AbacTags
) : ApplyResourceTags

/**
 * ABAC 资源标签已应用事件的默认实现。
 *
 * @property tags 标签映射
 */
data class DefaultResourceTagsApplied(
    override val tags: AbacTags
) : ResourceTagsApplied
