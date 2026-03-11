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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.wow.api.abac.AbacTagKey
import me.ahoo.wow.api.abac.AbacTagValue
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.wildcard
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.serialization.state.StateAggregateRecords.TAGS
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * ABAC 查询过滤器。
 *
 * 在查询快照（Snapshot）时，根据当前上下文中的主体（Principal）标签
 * 自动注入权限过滤条件，实现基于属性的访问控制。
 *
 * ## 权限匹配规则
 *
 * | 主体标签 | 资源标签 | 匹配结果 |
 * |---------|---------|---------|
 * | wildcard (`["*"]`) | 任意 | ✅ 匹配 |
 * | `["a", "b"]` | `["a"]` | ✅ 匹配 |
 * | `["a", "b"]` | `["c"]` | ❌ 不匹配 |
 * | 任意 | 无该 key | ✅ 匹配（资源公开） |
 *
 * @see SnapshotQueryFilter
 */
@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class)
abstract class AbacQueryFilter : SnapshotQueryFilter {
    companion object {
        /**
         * 将单个标签条目转换为查询条件。
         *
         * - wildcard：key 存在即可（通配）
         * - 非 wildcard：(key 不存在 OR 值在列表中)
         *
         * @return 嵌套查询条件
         */
        fun Map.Entry<AbacTagKey, AbacTagValue>.toCondition(): Condition =
            condition {
                nested(TAGS)
                if (value.wildcard) {
                    key.exists(true)
                } else {
                    or {
                        key.exists(false)
                        key isIn value
                    }
                }
            }

        /**
         * 将标签集合转换为 AND 查询条件。
         *
         * 所有标签 key 必须同时满足（AND 逻辑）。
         *
         * @return 所有标签条件的 AND 组合
         */
        fun AbacTags.toCondition(): Condition =
            condition {
                and {
                    for (tag in this@toCondition) {
                        condition(tag.toCondition())
                    }
                }
            }
    }

    /**
     * 从当前上下文获取主体的 ABAC 标签。
     *
     * @param context 查询上下文，可用于提取标签来源（如请求头、用户信息等）
     * @return 主体的标签映射
     */
    abstract fun ContextView.getPrincipalTags(context: QueryContext<*, *>): AbacTags

    /**
     * 从当前上下文解析 ABAC 查询条件。
     *
     * @param context 查询上下文
     * @return 若主体无标签，返回全匹配（不过滤）；
     *         否则返回所有标签的 AND 条件
     */
    open fun ContextView.resolveCondition(context: QueryContext<*, *>): Condition {
        val principalTags = getPrincipalTags(context)
        if (principalTags.isEmpty()) {
            return Condition.all()
        }
        return principalTags.toCondition()
    }

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>
    ): Mono<Void> {
        return Mono.deferContextual {
            val abacCondition = it.resolveCondition(context)
            if (abacCondition.operator == Operator.ALL) {
                return@deferContextual next.filter(context)
            }
            context.asRewritableQuery().rewriteQuery { query ->
                query.appendCondition(abacCondition)
            }
            next.filter(context)
        }
    }
}
