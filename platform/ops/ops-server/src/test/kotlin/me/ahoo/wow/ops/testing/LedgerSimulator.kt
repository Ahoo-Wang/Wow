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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.ops.api.OpsContext
import me.ahoo.wow.ops.api.parked.ParkedEntry
import me.ahoo.wow.ops.api.parked.ParkedResolution
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.command.CommandMessages
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.ledger.LedgerPublisher
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.util.UUID

/**
 * The owning services' ledgers, simulated on the ops node's in-memory topics ([InMemoryOpsTopics]): [publish] and
 * [resolve] send `ops.parked.RecordParked` and `RecordResolved` to `wow.ops.commands` as a service's ledger does
 * (`wow.ops.publish.enabled`), and [ledgerCommands] reads what the ops saga sent back to an owning context.
 */
class LedgerSimulator(manifest: Manifest, private val topics: InMemoryTopics) {
    private val sender = CommandSender(ContractDirectory(manifest), SourceTopics(topics, producer = topics))

    /** Publishes [entry] to `wow.ops.commands` as an owning service's ledger does; the id of its ops aggregate. */
    suspend fun publish(entry: ParkedEntry): String {
        val id = Ledger.parkedId(entry.service, entry.subscription, entry.aggregate.toRef())
        record(id, Ledger.RECORD_PARKED, mapOf(Ledger.ENTRY to entry))
        return id
    }

    /** Publishes that [resolution]'s entry is resolved, as the owning service's ledger does; the entry's id. */
    suspend fun resolve(resolution: ParkedResolution): String {
        val id = Ledger.parkedId(resolution.service, resolution.subscription, resolution.aggregate.toRef())
        record(id, Ledger.RECORD_RESOLVED, mapOf(Ledger.RESOLUTION to resolution))
        return id
    }

    private suspend fun record(id: String, command: String, body: Any) {
        val ref = AggregateType(OpsContext.NAME, OpsContext.PARKED).ref(Caller.DEFAULT_TENANT, id)
        val wire = CommandWire.of(
            type = "${ref.type}.$command",
            id = UUID.randomUUID().toString(),
            requestId = UUID.randomUUID().toString(),
            ref = ref,
            body = WowJson.mapper.valueToTree(body),
            operator = LedgerPublisher.OPERATOR,
        )
        sender.send(wire)
    }

    /**
     * Refuses [command], a ledger command the ops saga sent, with [code] and [message], as the owning service's intake
     * does (decision D226): the receipt of its rejection goes back to `wow.<replyTo.context>.receipts`.
     */
    @OptIn(InternalWowApi::class) // A receipt as the owning service's intake writes it.
    suspend fun refuse(command: CommandWire, code: String, message: String) {
        val replyTo = checkNotNull(command.replyTo) { "${command.type} ${command.id} asks for no receipt." }
        val rejected = CommandRejected<Any>(
            commandId = command.id,
            commandType = command.type,
            ref = command.ref,
            error = ErrorInfo(code, message),
            causation = checkNotNull(command.causation),
            executionId = replyTo.executionId(),
            operator = command.operator,
            scope = command.scope,
        )
        topics.send("wow.${replyTo.context}.receipts", CommandMessages.receipt(rejected))
    }

    /** The ledger's system commands the ops saga sent to [context]'s command topic, in their order per partition. */
    fun ledgerCommands(context: String): List<CommandWire> = topics.messages("wow.$context.commands")
        .map { CommandMessages.commandOf(EnvelopeCodec.decode(it.value)) }
        .filter { it.type.startsWith(Ledger.NAMESPACE) }
}

/**
 * The [LedgerSimulator] over HTTP, for the ops console's end-to-end test (`platform/ops/console/e2e`), which runs the
 * server in its own JVM with this configuration among `--spring.main.sources`: no service's ledger is there to park
 * anything, so the test publishes the entries it acts on.
 *
 * - `POST /testing/ledger/parked`: a [ParkedEntry]; answers `{"id": <the entry's ops id>}`.
 * - `POST /testing/ledger/resolved`: a [ParkedResolution]; answers the same.
 * - `GET /testing/ledger/commands/{context}`: the ledger's system commands sent to the context, `[{type, id, body}]`.
 *
 * It lives in the server's test classes only: nothing of it ships.
 */
@Configuration(proxyBeanMethods = false)
class LedgerSimulatorRoutes {
    @Bean
    fun ledgerSimulator(manifest: Manifest, topics: InMemoryTopics): LedgerSimulator = LedgerSimulator(manifest, topics)

    @Bean
    fun ledgerSimulatorRouter(ledger: LedgerSimulator): RouterFunction<ServerResponse> = coRouter {
        POST("$PATH/parked") { request ->
            val entry = WowJson.mapper.readValue(request.awaitBody<String>(), ParkedEntry::class.java)
            answer(mapOf("id" to ledger.publish(entry)))
        }
        POST("$PATH/resolved") { request ->
            val resolution = WowJson.mapper.readValue(request.awaitBody<String>(), ParkedResolution::class.java)
            answer(mapOf("id" to ledger.resolve(resolution)))
        }
        GET("$PATH/commands/{context}") { request ->
            val sent = ledger.ledgerCommands(request.pathVariable("context")).map {
                mapOf("type" to it.type, "id" to it.ref.id, "body" to it.body)
            }
            answer(sent)
        }
    }

    private suspend fun answer(body: Any): ServerResponse = ServerResponse.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValueAndAwait(WowJson.mapper.writeValueAsString(body))

    companion object {
        const val PATH: String = "/testing/ledger"
    }
}
