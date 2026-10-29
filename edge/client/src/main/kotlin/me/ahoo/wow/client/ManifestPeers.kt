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

package me.ahoo.wow.client

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The peers described by their Manifest alone (`wow.peers.<context>.contract: manifest`):
 * the [contexts] a [NamedCommand] is sent to, each one's Manifest read from its `GET /wow/manifest` on the port
 * [transport]'s mode calls, as the caller of the command that needs it, and kept for [ttl]. A command it does not
 * declare is read again once, for a peer deployed since, before it is refused.
 */
internal class ManifestPeers(
    val contexts: Set<String>,
    private val transport: HttpTransport,
    private val ttl: Duration = DEFAULT_TTL,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private class Read(val manifest: Manifest, val at: TimeMark)

    private val reads = ConcurrentHashMap<String, Read>()

    /** Whether [context] is a peer described by its Manifest. */
    fun describes(context: String): Boolean = context in contexts

    /** The Manifest of [context], read as [caller] asserting [call] in service mode, or kept from a read before. */
    suspend fun manifestOf(context: String, caller: Caller, call: CallWire?): Manifest {
        require(describes(context)) {
            "$context is not described by its Manifest: list it in wow.peers with contract: manifest."
        }
        return reads[context]?.takeIf { it.at.elapsedNow() < ttl }?.manifest ?: fetch(context, caller, call)
    }

    /**
     * The command [command] is, in the Manifest of the context of [type], the aggregate type it is sent to, read as
     * [caller] asserting [call] in service mode; an [IllegalArgumentException] when the Manifest does not declare it.
     */
    suspend fun commandOf(
        command: NamedCommand,
        type: AggregateType,
        caller: Caller,
        call: CallWire?,
    ): CommandManifest {
        require(command.target == type.toString()) {
            "The named command ${command.type} is not one of $type, the aggregate it is sent to."
        }
        val context = type.context
        val fresh = reads[context]?.takeIf { it.at.elapsedNow() < ttl }
        val manifest = fresh?.manifest ?: fetch(context, caller, call)
        // A Manifest kept from before may not know a command the peer was deployed with since: read it again once.
        return declared(manifest, command, type)
            ?: fresh?.let { declared(fetch(context, caller, call), command, type) }
            ?: throw IllegalArgumentException(
                "The Manifest of $context does not declare the command ${command.name} of $type."
            )
    }

    private fun declared(manifest: Manifest, command: NamedCommand, type: AggregateType): CommandManifest? =
        manifest.contexts.firstOrNull { it.name == type.context }
            ?.aggregates?.firstOrNull { it.name == type.name }
            ?.commands?.firstOrNull { it.name == command.name || command.name in it.aliases || command.type in it.aliases }

    private suspend fun fetch(context: String, caller: Caller, call: CallWire?): Manifest {
        val request = PeerRequest(context = context, path = MANIFEST_PATH, caller = caller, call = call)
        return when (val answer = transport.exchange(request, Exchange.QUERY)) {
            is Answer.Ok -> ManifestTree.read(ClientJson.mapper.readValue(answer.value, Map::class.java)) {
                peersLogger.warn { "The Manifest of $context: $it" }
            }
                .also { reads[context] = Read(it, time.markNow()) }
            is Answer.Problem -> throw answer.problem.exception("The Manifest of $context")
        }
    }

    companion object {
        /** How long a peer's Manifest is kept: a minute. */
        val DEFAULT_TTL: Duration = 1.minutes

        /** Where a node serves its Manifest, on either port. */
        const val MANIFEST_PATH: String = "/wow/manifest"
    }
}

private val peersLogger = KotlinLogging.logger {}
