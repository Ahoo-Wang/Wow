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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.manifest.ClassPathContracts
import me.ahoo.wow.manifest.CommandIndex
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.json.WowJson
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * The typed clients of the peers' HTTP routes, all on one JDK [HttpClient] and one
 * [EndpointResolver], in one [ClientMode]: [commands] sends command objects as the location-transparent
 * [CommandClient] does in process, and [queries] reads their snapshots, events, datasets and states as the [QueryApi]
 * does. A peer's commands and state classes are found in the contract its api jar carries, read from
 * the class path: a consumer depends on the peer's `*-api` and writes no path.
 *
 * Build it with [builder]:
 *
 * ```kotlin
 * val client = WowClient.builder().
 *     endpoints(EndpointResolver.of(emptyMap(), mapOf("ordering" to URI.create("http://ordering:9090")))).
 *     service("basket-service").
 *     build()
 * client.commands.of(caller).wait(SubmitOrder(...), OrderingContract.ORDER.ref(tenant, id))
 * ```
 */
class WowClient private constructor(
    private val http: HttpCommandClient,
    /** The query client of the peers' read routes, for a caller ([QueryApi.of]); see [HttpQueryApi]. */
    val queries: QueryApi,
) {
    /** The command client, sending as an anonymous caller until [CommandClient.of] gives it another. */
    val commands: CommandClient get() = http

    /**
     * The Manifest of [context], a peer described by its Manifest alone ([Builder.manifestPeers]), read from its
     * `GET /wow/manifest` as [caller] and kept a minute, as the [NamedCommand]s to it are checked against: what a
     * console or a gateway offers of its commands.
     */
    suspend fun manifestOf(context: String, caller: Caller): Manifest =
        (http.of(caller) as HttpCommandClient).manifestOf(context)

    /** How a [WowClient] is built; every setting has a default. */
    class Builder internal constructor() {
        private var endpoints: EndpointResolver = EndpointResolver.NONE
        private var mode: ClientMode = ClientMode.Service(null)
        private var httpClient: HttpClient? = null
        private var connectTimeout: Duration = HttpCommandCaller.DEFAULT_CONNECT_TIMEOUT
        private var timeout: Duration = DEFAULT_TIMEOUT
        private val timeouts = LinkedHashMap<String, Duration>()
        private var retry: RetryPolicy = RetryPolicy.DEFAULT
        private var classLoader: ClassLoader? = null
        private var contracts: List<ManifestFragment>? = null
        private var ids: GlobalIdGenerator = GlobalIdGenerator.UUID_V7
        private var manifestPeers: Set<String> = emptySet()

        /** Where each context's service is (none by default: every call fails until it is given). */
        fun endpoints(endpoints: EndpointResolver): Builder = apply { this.endpoints = endpoints }

        /**
         * Service mode ([ClientMode.Service], the default) as the service named [name], the `spring.application.name`
         * the peers list it by (`Wow-Service`); a client that names none is refused by every peer.
         */
        fun service(name: String): Builder = apply {
            require(name.isNotBlank()) { "A service is named." }
            mode = ClientMode.Service(name)
        }

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

        /** How long a request to the peer [context] may take until it answers, over [timeout]. */
        fun timeout(context: String, timeout: Duration): Builder = apply {
            require(timeout.isPositive()) { "A request's timeout is positive." }
            timeouts[context] = timeout
        }

        /** How a problem a retry may get past is retried ([RetryPolicy.DEFAULT] by default). */
        fun retry(retry: RetryPolicy): Builder = apply { this.retry = retry }

        /** The class loader whose api jars carry the peers' contracts: the thread's context class loader by default. */
        fun classLoader(classLoader: ClassLoader): Builder = apply { this.classLoader = classLoader }

        /**
         * The peer contexts described by their Manifest alone (none by default), called without their api jars: each
         * takes [NamedCommand]s only, which its `GET /wow/manifest` must declare.
         */
        fun manifestPeers(contexts: Set<String>): Builder = apply { manifestPeers = contexts.toSet() }

        /**
         * The peers' contracts, which name their commands and models: those on [classLoader] by default; Wow's host
         * gives the ones its node read.
         */
        @InternalWowApi
        fun contracts(contracts: List<ManifestFragment>): Builder = apply { this.contracts = contracts.toList() }

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
                timeouts.toMap(),
            )
            // The class path's contracts through:manifest's one reader, named by its one rule.
            val contracts = contracts ?: ClassPathContracts.load(
                classLoader ?: Thread.currentThread().contextClassLoader,
            ) { bytes -> WowJson.mapper.readValue(bytes, Map::class.java) }
            val manifests = contracts.map { it.manifest }
            val names = CommandIndex(manifests)
            return WowClient(
                HttpCommandClient(
                    transport,
                    names,
                    ids,
                    described = manifestPeers.takeIf { it.isNotEmpty() }?.let { ManifestPeers(it, transport) },
                ),
                HttpQueryApi(transport, PeerModels.of(manifests), ids),
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
