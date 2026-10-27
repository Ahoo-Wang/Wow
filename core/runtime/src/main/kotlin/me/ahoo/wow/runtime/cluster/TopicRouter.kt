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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.currentCoroutineContext
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.command.CommandMessages
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.WaitContext

/**
 * The `Kafka` router of one context: the commands of its [types] are
 * admitted by writing them to the context's command [topic], whose partitions are the context's log partitions
 * ([partitions]), each in the one the log's [Partitioner] gives its aggregate, so the owner of the aggregate's log
 * partition reads and executes it ([me.ahoo.wow.runtime.command.CommandIntake]) with no forwarding, and one
 * aggregate's commands keep their order.
 *
 * [send] returns once the broker acknowledged the command: it is then durable, ACCEPTED means it will not be lost,
 * and the node that admitted it may stop at once. It throws when the broker did not acknowledge it; the command may
 * still have been written, and a retry with the same request id is not decided twice.
 */
@InternalWowApi
class TopicRouter(
    val types: Set<AggregateType>,
    val topic: String,
    private val partitions: Int,
    private val producer: TopicProducer,
) {
    init {
        require(types.isNotEmpty()) { "A Kafka router routes some aggregate types." }
        require(types.map { it.context }.distinct().size == 1) { "A Kafka router routes the types of one context." }
        require(partitions >= 1) { "A log has at least one partition." }
    }

    /** Whether the commands of [type] go through the command topic. */
    fun routes(type: AggregateType): Boolean = type in types

    /**
     * Writes [command], of [model]'s aggregate, to the command topic with [wait] and the trace it is sent in,
     * and waits for the broker.
     */
    suspend fun send(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?) {
        require(routes(command.ref.type)) { "${command.ref.type} is not routed through $topic." }
        val partition = Partitioner.partitionOf(command.ref, partitions)
        val trace = currentCoroutineContext().trace
        producer.send(topic, CommandMessages.command(ClusterCodec.wire(model, command), partition, wait, trace))
    }
}
