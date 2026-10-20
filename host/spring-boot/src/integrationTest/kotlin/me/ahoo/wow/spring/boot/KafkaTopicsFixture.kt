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

package me.ahoo.wow.spring.boot

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.kafka.KafkaOptions
import me.ahoo.wow.kafka.KafkaPartitionedSource
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import kotlin.time.Duration.Companion.seconds

/**
 * Creates [topics] and waits until a consumer's metadata sees each with all its partitions, as a node's
 * [KafkaPartitionedSource] reads them at its start: the admin's acknowledgement does not mean every broker's metadata
 * has the topic yet, and a node started at once may find it missing.
 */
internal fun Admin.createVisible(options: KafkaOptions, vararg topics: NewTopic) {
    createTopics(topics.toList()).all().get()
    runBlocking {
        KafkaPartitionedSource.create(options).use { source ->
            withTimeout(30.seconds) {
                while (!topics.all { source.partitions(it.name()).size == it.numPartitions() }) {
                    delay(50)
                }
            }
        }
    }
}
