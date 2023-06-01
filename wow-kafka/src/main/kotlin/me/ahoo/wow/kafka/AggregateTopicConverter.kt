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

package me.ahoo.wow.kafka

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.asNamedAggregateString

fun interface AggregateTopicConverter {
    fun convert(namedAggregate: NamedAggregate): String
}

interface CommandTopicConverter : AggregateTopicConverter
interface EventStreamTopicConverter : AggregateTopicConverter
interface SnapshotTopicConverter : AggregateTopicConverter

class DefaultCommandTopicConverter(private val topicPrefix: String = Wow.WOW_PREFIX) : CommandTopicConverter {
    companion object {
        const val COMMAND_TOPIC_SUFFIX = "command"
    }

    override fun convert(namedAggregate: NamedAggregate): String {
        return "${topicPrefix}${namedAggregate.asNamedAggregateString()}.$COMMAND_TOPIC_SUFFIX"
    }
}

class DefaultEventStreamTopicConverter(private val topicPrefix: String = Wow.WOW_PREFIX) : EventStreamTopicConverter {
    companion object {
        const val EVENT_TOPIC_SUFFIX = "event"
    }

    override fun convert(namedAggregate: NamedAggregate): String {
        return "${topicPrefix}${namedAggregate.asNamedAggregateString()}.$EVENT_TOPIC_SUFFIX"
    }
}

class DefaultSnapshotTopicConverter(private val topicPrefix: String = Wow.WOW_PREFIX) : SnapshotTopicConverter {
    companion object {
        const val SNAPSHOT_TOPIC_SUFFIX = "snapshot"
    }

    override fun convert(namedAggregate: NamedAggregate): String {
        return "${topicPrefix}${namedAggregate.asNamedAggregateString()}.$SNAPSHOT_TOPIC_SUFFIX"
    }
}
