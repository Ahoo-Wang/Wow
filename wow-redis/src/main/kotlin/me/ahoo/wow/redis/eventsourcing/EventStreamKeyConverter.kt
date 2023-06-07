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
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.unwrap
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.wrap

object EventStreamKeyConverter : AggregateKeyConverter {
    private const val ID_DELIMITER = "@"
    fun NamedAggregate.toKeyPrefix(): String {
        return "${asStringWithAlias()}${DELIMITER}es$DELIMITER"
    }

    fun toAggregateIdKey(aggregateId: AggregateId): String {
        return "${aggregateId.id}$ID_DELIMITER${aggregateId.tenantId}".wrap()
    }

    fun toAggregateId(namedAggregate: NamedAggregate, key: String): AggregateId {
        val prefix = namedAggregate.toKeyPrefix()
        val idWithTenantId = key.removePrefix(prefix).unwrap()
        idWithTenantId.split(ID_DELIMITER).let {
            return namedAggregate.asAggregateId(it[0], it[1])
        }
    }

    override fun convert(aggregateId: AggregateId): String {
        return "${aggregateId.toKeyPrefix()}${toAggregateIdKey(aggregateId)}"
    }
}
