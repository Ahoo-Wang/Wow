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

package me.ahoo.wow.redis.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.mod
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.unwrap
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.wrap

object EventStreamKeyConverter : AggregateKeyConverter {
    const val AGGREGATE_ID_INDEX_BUCKETS = 128
    const val ID_DELIMITER = "@"
    private const val INDEX_MEMBER_DELIMITER = "\u0000"
    private const val INDEX_MEMBER_AFTER_ID_DELIMITER = "\u0001"

    fun NamedAggregate.toHashTag(bucket: Int): String {
        return "${toStringWithAlias()}${DELIMITER}es$DELIMITER$bucket".wrap()
    }

    fun AggregateId.toKeyPrefix(): String {
        return "${toHashTag(mod(AGGREGATE_ID_INDEX_BUCKETS))}$DELIMITER"
    }

    fun NamedAggregate.toAggregateIdIndexKey(bucket: Int): String {
        return "${toHashTag(bucket)}${DELIMITER}ids"
    }

    fun AggregateId.toAggregateIdIndexKey(): String {
        return namedAggregate.toAggregateIdIndexKey(mod(AGGREGATE_ID_INDEX_BUCKETS))
    }

    fun toAggregateIdIndexMember(aggregateId: AggregateId): String {
        return "${aggregateId.id}$INDEX_MEMBER_DELIMITER${aggregateId.tenantId}"
    }

    fun toAggregateIdIndexMemberLowerBound(afterId: String): String {
        return "$afterId$INDEX_MEMBER_AFTER_ID_DELIMITER"
    }

    fun toAggregateIdFromIndexMember(namedAggregate: NamedAggregate, member: String): AggregateId {
        val delimiterIndex = member.indexOf(INDEX_MEMBER_DELIMITER)
        require(delimiterIndex > 0) {
            "Invalid aggregate id index member:$member"
        }
        val id = member.substring(0, delimiterIndex)
        val tenantId = member.substring(delimiterIndex + INDEX_MEMBER_DELIMITER.length)
        return namedAggregate.aggregateId(id, tenantId)
    }

    fun AggregateId.toKey(): String {
        return "${id}$ID_DELIMITER$tenantId".wrap()
    }

    override fun convert(aggregateId: AggregateId): String {
        return "${aggregateId.toKeyPrefix()}${aggregateId.id}$ID_DELIMITER${aggregateId.tenantId}"
    }

    fun toAggregateId(namedAggregate: NamedAggregate, key: String): AggregateId {
        val hashTagPrefix = "${RedisWrappedKey.KEY_PREFIX}${namedAggregate.toStringWithAlias()}${DELIMITER}es$DELIMITER"
        require(key.startsWith(hashTagPrefix)) { "Invalid key:$key" }
        val idWithTenantId = key.substringAfter("${RedisWrappedKey.KEY_SUFFIX}$DELIMITER").unwrap()
        idWithTenantId.split(ID_DELIMITER).let {
            require(it.size == 2) { "Invalid key:$key" }
            return namedAggregate.aggregateId(it[0], it[1])
        }
    }
}
