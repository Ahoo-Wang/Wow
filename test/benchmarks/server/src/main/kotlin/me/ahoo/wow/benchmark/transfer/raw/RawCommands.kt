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

package me.ahoo.wow.benchmark.transfer.raw

import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import org.springframework.context.annotation.Bean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * The commands Wow's run sends MongoDB, by command and collection (`update wow_snapshot__transfer__account`), so the
 * calibration takes Wow's batch shape from its log's inserts and its snapshots' updates alone: the node's checkpoint
 * and lease writes are commands of their own collections. RawStoreBench passes it as a source of Wow's run, beside
 * ComponentCounters; the server hands every CommandListener to its client.
 */
class RawCommands {
    private val counts = ConcurrentHashMap<String, LongAdder>()

    @Bean
    fun rawCommandListener(): CommandListener = object : CommandListener {
        override fun commandStarted(event: CommandStartedEvent) {
            val target = event.command[event.commandName]?.takeIf { it.isString }?.asString()?.value ?: return
            counts.computeIfAbsent("${event.commandName} $target") { LongAdder() }.increment()
        }
    }

    /** Every count so far, `commands: <command> <collection>`. */
    fun snapshot(): Map<String, Double> = counts.entries.associate { (key, count) -> "commands: $key" to count.sum().toDouble() }
}
