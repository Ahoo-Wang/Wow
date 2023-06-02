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

package me.ahoo.wow.redis

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate

fun interface AggregateTopicConverter {
    fun toTopic(namedAggregate: NamedAggregate): String
}

interface AggregateKeyConverter : AggregateTopicConverter {
    fun toKey(aggregateId: AggregateId): String
}

const val DELIMITER = ":"

class SnapshotKeyConverter : AggregateKeyConverter {
    override fun toKey(aggregateId: AggregateId): String {
        return "${aggregateId.contextName}$DELIMITER${aggregateId.aggregateName}"
    }

    override fun toTopic(namedAggregate: NamedAggregate): String {
        return "${namedAggregate.contextName}$DELIMITER${namedAggregate.aggregateName}"
    }
}