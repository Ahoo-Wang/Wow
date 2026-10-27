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

package me.ahoo.wow.ops.testing

import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.node.SourceTopics
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The test's command transport in place of Kafka: the services' records come in on the in-memory `wow.ops.commands`,
 * which the node's intake reads, and the ledger's system commands go out to the owning contexts' in-memory command
 * topics, where the tests read them. Every topic has the log's partitions, as an intake's topic must.
 */
@Configuration(proxyBeanMethods = false)
class InMemoryOpsTopics {
    @Bean
    fun inMemoryTopics(@Value("\${wow.log.partitions:64}") partitions: Int): InMemoryTopics = InMemoryTopics(partitions)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics.of(topics, producer = topics, intake = true)
}
