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

package me.ahoo.wow.messaging.transport

import me.ahoo.wow.api.modeling.NamedAggregate
import java.util.concurrent.ConcurrentHashMap

/**
 * The topic that carries one kind of message of an aggregate on one backend. Topic names are frozen wire format:
 * Kafka `wow.<context>.<aggregate>.command`, Redis `<context>.<aggregate>:command`, and likewise per kind.
 */
fun interface TopicNaming {
    fun topicOf(namedAggregate: NamedAggregate): String
}

/**
 * This naming computed once per aggregate, which holds because a topic depends only on the context and aggregate
 * names.
 */
fun TopicNaming.memoized(): TopicNaming =
    this as? MemoizedTopicNaming ?: MemoizedTopicNaming(this)

private class MemoizedTopicNaming(private val delegate: TopicNaming) : TopicNaming {
    private val topics = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    override fun topicOf(namedAggregate: NamedAggregate): String {
        val contextTopics = topics[namedAggregate.contextName]
            ?: topics.computeIfAbsent(namedAggregate.contextName) { ConcurrentHashMap() }
        return contextTopics[namedAggregate.aggregateName]
            ?: contextTopics.computeIfAbsent(namedAggregate.aggregateName) {
                delegate.topicOf(namedAggregate)
            }
    }
}
