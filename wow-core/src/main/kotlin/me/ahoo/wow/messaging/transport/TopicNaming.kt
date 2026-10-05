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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The topic that carries one kind of message of an aggregate on one backend. Topic names are frozen wire format:
 * Kafka `wow.<context>.<aggregate>.command`, Redis `<context>.<aggregate>:command`, and likewise per kind.
 */
fun interface TopicNaming {
    fun topicOf(namedAggregate: NamedAggregate): String
}

/**
 * This naming computed once per aggregate, which holds because a topic depends only on the context and aggregate
 * names. At most [maxAggregates] aggregates are kept, so records naming arbitrary aggregates cannot grow it without
 * bound; beyond that a topic is computed on every call.
 */
fun TopicNaming.memoized(maxAggregates: Int = DEFAULT_MEMOIZED_AGGREGATES): TopicNaming =
    this as? MemoizedTopicNaming ?: MemoizedTopicNaming(this, maxAggregates)

const val DEFAULT_MEMOIZED_AGGREGATES: Int = 1024

private class MemoizedTopicNaming(
    private val delegate: TopicNaming,
    private val maxAggregates: Int,
) : TopicNaming {
    private val topics = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val size = AtomicInteger()

    override fun topicOf(namedAggregate: NamedAggregate): String {
        topics[namedAggregate.contextName]?.get(namedAggregate.aggregateName)?.let {
            return it
        }
        if (size.get() >= maxAggregates) {
            return delegate.topicOf(namedAggregate)
        }
        return topics.computeIfAbsent(namedAggregate.contextName) { ConcurrentHashMap() }
            .computeIfAbsent(namedAggregate.aggregateName) {
                size.incrementAndGet()
                delegate.topicOf(namedAggregate)
            }
    }
}
