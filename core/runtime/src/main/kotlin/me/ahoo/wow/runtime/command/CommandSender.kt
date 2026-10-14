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

package me.ahoo.wow.runtime.command

import kotlinx.coroutines.currentCoroutineContext
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends commands to the command topics of other contexts (design 02 §2; decisions D33, D135, D137): how a saga's
 * command reaches an aggregate of a context outside its own.
 *
 * - **Type**: the command's full type name (`ctx.agg.Command`) is the one the [contracts] give its class
 *   ([ContractDirectory.commandTypeOf] of the schema `#/$defs/<binary name>`, decision D157): the target's Manifest,
 *   which a peer's reaches the service through `wowPeers` (decision D139). A class no Manifest declares, or one of
 *   another aggregate than the command's, fails the send.
 * - **Topic and partition**: `wow.<ctx>.commands` after the prefix ([SourceTopics.commandsOf]), in the partition the
 *   log's [Partitioner] gives the aggregate among the topic's partitions, which the target keeps equal to its log's
 *   (decision D135), so the owner of the aggregate's log partition reads it. The partition count is read once per
 *   topic; a topic without partitions fails the send.
 * - **Durable ACCEPTED**: [send] returns once the broker has acknowledged the message (decision D33); it throws when
 *   it has not, and the caller retries with the same command, which the target does not decide twice.
 * - **Trace**: the message carries the trace the sender runs in (decision D174), which the target's intake continues.
 */
class CommandSender(private val contracts: ContractDirectory, private val topics: SourceTopics) {
    private val producer: TopicProducer = requireNotNull(topics.producer) { "Commands are sent through a producer." }
    private val partitions = ConcurrentHashMap<String, Int>()

    /** Sends [command] to its context's command topic, with [wait] and the [replyTo] of a rejection. */
    suspend fun send(command: Command<*>, wait: WaitContext? = null, replyTo: ReplyTo? = null) =
        send(ClusterCodec.wire(typeOf(command), command, replyTo), wait)

    /**
     * Sends [wire], a command already in its wire form and named (a timer's, decision D166), to its context's command
     * topic, with [wait].
     */
    suspend fun send(wire: CommandWire, wait: WaitContext? = null) {
        val topic = topics.commandsOf(wire.ref.type.context)
        producer.send(
            topic,
            CommandMessages.command(
                wire,
                Partitioner.partitionOf(wire.ref, partitionsOf(topic)),
                wait,
                currentCoroutineContext().trace,
            ),
        )
    }

    /**
     * The full type name of [command], as the contracts give its class; a system command by its system name: a
     * lifecycle one or a schedule's `wow.schedule.Set` or `Cancel` (decision D198), which every aggregate decides, or
     * a ledger's `wow.ledger.*` (decision D141), which the target's intake runs.
     */
    fun typeOf(command: Command<*>): String {
        val type = command.body.javaClass
        (Lifecycle.nameOf(type) ?: Schedules.nameOf(type) ?: Ledger.nameOf(type))?.let { return it }
        val named = checkNotNull(contracts.commandTypeOf(Manifest.schemaRef(type.name))) {
            "No Manifest of the service or its peers declares the command ${type.name} sent to ${command.ref}: add " +
                "the service of ${command.ref.type.context} to wowPeers (decision D139)."
        }
        check(named.startsWith("${command.ref.type}.")) {
            "${type.name} is the command $named, not one of ${command.ref.type}, the aggregate it is sent to."
        }
        return named
    }

    private suspend fun partitionsOf(topic: String): Int = partitions[topic] ?: topics.source.partitions(topic).size
        .also { count ->
            check(count > 0) { "Topic $topic has no partitions: its context creates it with its log's (decision D135)." }
            partitions[topic] = count
        }
}
