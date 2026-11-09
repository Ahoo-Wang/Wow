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

import java.net.URI

/**
 * The port of a peer's service a client calls: its [PUBLIC] routes, which forward the end
 * user's credential, or its [INTERNAL] port, which takes a service's synchronous calls and peer reads (`Wow-Call`).
 */
enum class Port {
    PUBLIC,
    INTERNAL,
}

/**
 * Where a client finds the service of a context: the base URL of its [Port], such as
 * `http://ordering:8080`. Wow keeps no registry and balances no load: a resolver that picks an instance per call (from
 * a service registry, DNS or a sidecar) is the application's, and is asked again on every attempt, so a retry may
 * reach another instance.
 *
 * Such a resolver overrides [reaches] to say which contexts it serves. A lookup of one of them that finds no instance
 * is then an outage: the call fails as `wow.peer.unavailable`, which a client retries and a saga's subscription waits
 * out. Without it, an empty answer is taken for a context the client was given no URL of, the code's mistake.
 */
fun interface EndpointResolver {
    suspend fun resolve(context: String, port: Port): URI?

    /**
     * Every base URL of [context]'s [port], the nodes a request may go to in turn: the client rotates through them and
     * fails over to the next on a connection that was not made, or on a node that refused before handling.
     * [resolve]'s alone by default. None is an outage for a context it [reaches], a missing URL for any other.
     */
    suspend fun resolveAll(context: String, port: Port): List<URI> = listOfNotNull(resolve(context, port))

    /**
     * Whether [context]'s [port] may be found, without looking it up: a node checks at startup that its sagas reach
     * the contexts they call, and a peer found by DNS need not be up then. Whether [resolve] finds one, by default: a
     * resolver that looks instances up overrides it, or a lookup that finds none fails as a missing URL would.
     */
    suspend fun reaches(context: String, port: Port): Boolean = resolve(context, port) != null

    /** Hears that no base URL of [context]'s [port] took a connection, so a resolver may look again before it would. */
    fun unreachable(context: String, port: Port) = Unit

    companion object {
        /** Resolves nothing: every call fails for want of an address. */
        @JvmField
        val NONE: EndpointResolver = EndpointResolver { _, _ -> null }

        /**
         * The fixed base URLs of each context's public routes ([publicUrls], `wow.peers.<context>.public-url`) and
         * its internal port ([internalUrls], `wow.peers.<context>.url`), each `http` or `https`.
         */
        @JvmStatic
        @JvmOverloads
        fun of(publicUrls: Map<String, URI>, internalUrls: Map<String, URI> = emptyMap()): EndpointResolver {
            val urls = mapOf(Port.PUBLIC to checked(publicUrls), Port.INTERNAL to checked(internalUrls))
            return EndpointResolver { context, port -> urls.getValue(port)[context] }
        }

        private fun checked(urls: Map<String, URI>): Map<String, URI> = urls.onEach { (context, uri) ->
            require(
                uri.scheme == "http" || uri.scheme == "https"
            ) { "The URL of context $context is http or https: $uri" }
        }
    }
}
