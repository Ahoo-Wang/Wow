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

internal object EventStreamKeyLayout {
    const val AGGREGATE_ID_INDEX_BUCKETS = 128
    private const val LAYOUT_PREFIX = "v2:es"
    private const val DELIMITER = ':'

    fun NamedAggregate.toHashTag(bucket: Int): String {
        return "{$LAYOUT_PREFIX$DELIMITER${CanonicalRedisKeyCodec.encodeScope(this)}$DELIMITER$bucket}"
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
        return CanonicalRedisKeyCodec.encodeIndexMember(aggregateId)
    }

    fun toAggregateIdIndexMemberLowerBound(afterId: String): String {
        return CanonicalRedisKeyCodec.indexMemberAfterId(afterId)
    }

    internal fun toAggregateIdIndexMemberPrefix(id: String): String {
        return CanonicalRedisKeyCodec.indexMemberPrefix(id)
    }

    fun toAggregateIdFromIndexMember(namedAggregate: NamedAggregate, member: String): AggregateId {
        return CanonicalRedisKeyCodec.decodeIndexMember(namedAggregate, member)
    }

    fun key(aggregateId: AggregateId): String {
        return "${aggregateId.toKeyPrefix()}${CanonicalRedisKeyCodec.encodeIdentity(aggregateId)}"
    }

    fun requestIndexKey(aggregateId: AggregateId): String = "${key(aggregateId)}:req_idx"
}
