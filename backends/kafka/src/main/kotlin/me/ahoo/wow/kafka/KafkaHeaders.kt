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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader
import java.util.concurrent.Executors

/** Headers as Kafka holds them, UTF-8 text both ways; a header without a value is left out, a repeated one's last wins. */
internal object KafkaHeaders {
    fun of(headers: Map<String, String>): List<RecordHeader> =
        headers.map { (name, value) -> RecordHeader(name, value.toByteArray(Charsets.UTF_8)) }

    fun of(headers: Headers): Map<String, String> = buildMap {
        headers.forEach { header -> header.value()?.let { put(header.key(), String(it, Charsets.UTF_8)) } }
    }
}

/** The blocking calls of `kafka-clients` run on virtual threads (decision D31), as wow-postgres runs JDBC. */
internal object VirtualThreads {
    val dispatcher: CoroutineDispatcher by lazy { Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher() }
}
