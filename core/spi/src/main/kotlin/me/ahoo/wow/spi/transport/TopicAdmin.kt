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

package me.ahoo.wow.spi.transport

/**
 * Describes and creates the topics of a broker (decision D168): the port the runtime validates and creates a node's
 * topics through at its start, as `wow.kafka.topics.ddl` says. Neither call creates a topic as a side effect of
 * asking about it.
 *
 * - [partitions] gives the partition count of each of [topics] that exists; one that does not is left out.
 * - [create] creates each topic of [topics] with its partition count, the broker's replication and topic settings
 *   being the implementation's; a topic that exists already, such as one another node created at the same moment,
 *   is left as it is. It returns once the created topics can be described.
 */
interface TopicAdmin {
    suspend fun partitions(topics: Set<String>): Map<String, Int>

    suspend fun create(topics: Map<String, Int>)
}
