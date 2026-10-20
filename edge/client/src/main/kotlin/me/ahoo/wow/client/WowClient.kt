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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.id.GlobalIdGenerator
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * The typed clients of the peers' HTTP routes (decisions D257, D259), all on one JDK [HttpClient] and one
 * [EndpointResolver], in one [ClientMode]: [commands] sends command objects as the location-transparent
 * [CommandClient] does in process, and [queries] reads their snapshots, events, datasets and states as the [QueryApi]
 * does. A peer's commands and state classes are found in the contract its api jar carries (decision D234), read from
 * the class path: a consumer depends on the peer's `*-api` and writes no path.
 *
 * Build it with [builder]:
 *
 * ```kotlin
 * val client = WowClient.builder()
 *     .endpoints(EndpointResolver.of(emptyMap(), mapOf("ordering" to URI.create("http://ordering:9090"))))
 *     .service(ServiceCredential.bearer(token))
 *     .build()
 * client.commands.of(caller).wait(SubmitOrder(...), OrderingContract.ORDER.ref(tenant, id))
 * ```
 */
class WowClient private constructor(
    /** The command client, sending as an anonymous caller until [CommandClient.of] gives it another. */
    val commands: CommandClient,
    /** The query client of the peers' read routes, for a caller ([QueryApi.of]); see [HttpQueryApi]. */
    val queries: QueryApi,
) {
    /** How a [WowClient] is built; every setting has a default. */
    class Builder internal constructor() {
        private var endpoints: EndpointResolver = EndpointResolver.NONE
        private var mode: ClientMode = ClientMode.Service(ServiceCredential.NONE)
        private var httpClient: HttpClient? = null
        private var connectTimeout: Duration = HttpCommandCaller.DEFAULT_CONNECT_TIMEOUT
        private var timeout: Duration = DEFAULT_TIMEOUT
        private var retry: RetryPolicy = RetryPolicy.DEFAULT
        private var classLoader: ClassLoader? = null
        private var commandTypes: CommandTypes? = null
        private var contracts: ContractDirectory? = null
        private var ids: GlobalIdGenerator = GlobalIdGenerator.UUID_V7

        /** Where each context's service is (none by default: every call fails until it is given). */
        fun endpoints(endpoints: EndpointResolver): Builder = apply { this.endpoints = endpoints }

        /** Service mode ([ClientMode.Service], the default), presenting [credential] (none by default). */
        fun service(credential: ServiceCredential): Builder = apply { mode = ClientMode.Service(credential) }

        /** User mode ([ClientMode.User]), forwarding the end user's [credential]. */
        fun user(credential: UserCredential): Builder = apply { mode = ClientMode.User(credential) }

        /** The JDK client to send with; by default one of HTTP/1.1 that connects within [connectTimeout]. */
        fun httpClient(httpClient: HttpClient): Builder = apply { this.httpClient = httpClient }

        /** [connectTimeout] of a [java.time.Duration], for Java callers. */
        fun connectTimeout(connectTimeout: java.time.Duration): Builder = connectTimeout(
            connectTimeout.toKotlinDuration()
        )

        /** [timeout] of a [java.time.Duration], for Java callers. */
        fun timeout(timeout: java.time.Duration): Builder = timeout(timeout.toKotlinDuration())

        /** How long the default client takes to connect: 2 seconds by default. */
        fun connectTimeout(connectTimeout: Duration): Builder = apply {
            require(connectTimeout.isPositive()) { "A connect timeout is positive." }
            this.connectTimeout = connectTimeout
        }

        /**
         * How long a request may take until the peer answers it (a stream's head): 30 seconds by default, longer for
         * a command whose wait is longer.
         */
        fun timeout(timeout: Duration): Builder = apply {
            require(timeout.isPositive()) { "A request's timeout is positive." }
            this.timeout = timeout
        }

        /** How a problem a retry may get past is retried ([RetryPolicy.DEFAULT] by default). */
        fun retry(retry: RetryPolicy): Builder = apply { this.retry = retry }

        /** The class loader whose api jars carry the peers' contracts: the thread's context class loader by default. */
        fun classLoader(classLoader: ClassLoader): Builder = apply { this.classLoader = classLoader }

        /** Names the commands as a node does, from its models and the contracts it knows: Wow's host gives its own. */
        @InternalWowApi
        fun commandTypes(commandTypes: CommandTypes): Builder = apply { this.commandTypes = commandTypes }

        /** The contracts the node knows, its peers' among them: Wow's host gives its own. */
        @InternalWowApi
        fun contracts(contracts: ContractDirectory): Builder = apply { this.contracts = contracts }

        /** The ids of new request ids and root commands: UUIDs of version 7 by default. */
        @InternalWowApi
        fun ids(ids: GlobalIdGenerator): Builder = apply { this.ids = ids }

        fun build(): WowClient {
            val transport = HttpTransport(
                endpoints,
                mode,
                httpClient ?: HttpCommandCaller.client(connectTimeout),
                timeout,
                retry,
            )
            val contracts = contracts
                ?: ContractDirectory.load(Manifest(), classLoader ?: Thread.currentThread().contextClassLoader)
            val names = commandTypes ?: ContractCommandTypes(contracts)
            return WowClient(
                HttpCommandClient(transport, names, ids),
                HttpQueryApi(transport, PeerModels.of(contracts.peers.map { it.manifest }), ids),
            )
        }
    }

    companion object {
        /** The default bound of a request: a wait of the default timeout (10 seconds) and then some. */
        val DEFAULT_TIMEOUT: Duration = 30.seconds

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/** Names the commands of the peers by the contracts on the class path (decision D234), as a node names them. */
internal class ContractCommandTypes(private val contracts: ContractDirectory) : CommandTypes {
    override fun nameOf(target: AggregateType, command: Class<*>): String? =
        contracts.commandTypeOf(Manifest.schemaRef(command.name))?.takeIf { it.startsWith("$target.") }

    override fun aggregatesOf(command: Class<*>): List<AggregateType> =
        contracts.aggregatesOf(Manifest.schemaRef(command.name))
}
