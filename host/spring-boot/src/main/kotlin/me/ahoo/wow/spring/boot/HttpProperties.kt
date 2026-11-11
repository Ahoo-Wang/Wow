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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.client.PeerMode
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.spi.config.configNotNull
import java.net.URI
import java.time.Duration
import kotlin.time.toJavaDuration
import me.ahoo.wow.client.RetryPolicy as ClientRetryPolicy

/** The HTTP edge: its routes and its error answers. */
@InternalWowApi
class HttpProperties {
    /**
     * The request headers copied into a command's headers, beside the standard request facts the edge records:
     * `app_id`, the application the caller resolver authenticated, and on the public port
     * `user_agent` and `remote_ip`; none of the three can be listed, and each fact is cut to 512 characters.
     * `remote_ip` is the server's remote address, which the host takes from the gateway's `X-Forwarded-For`
     * (`server.forward-headers-strategy` is `framework` unless the application sets it, `none` included).
     */
    var requestFacts: MutableSet<String> = LinkedHashSet()

    /** The client address and `User-Agent` of each command, recorded as request facts. */
    var clientFacts: ClientFacts = ClientFacts()

    /** The HTTP status of an error code, by code (bracketed in a property name: `wow.http.errors.[a.b].status`). */
    var errors: MutableMap<String, Error> = LinkedHashMap()

    /** The status of each code of [errors]; an entry without one refuses the start, naming its key. */
    fun errorStatuses(): Map<String, Int> = errors.mapValues { (code, error) ->
        val key = "wow.http.errors.[$code].status"
        configNotNull(error.status, key, "Set $key, 400 to 599, or remove the entry.") {
            "The error code $code has no status: an entry of wow.http.errors is the status its code is answered with."
        }
    }

    /** The problem+json answers of what fails on the application's own routes. */
    var globalErrors: GlobalErrors = GlobalErrors()

    /** The service's Manifest route. */
    var manifest: Manifest = Manifest()

    /** The OpenAPI document of the routes. */
    var openapi: OpenApi = OpenApi()

    /** The operator's routes of the node: its parked entries and its subscriptions. */
    var ops: HttpOps = HttpOps()

    @InternalWowApi
    class HttpOps {
        /**
         * Serves the operator's routes on the public port: the parked entries under `/wow/ops/parked`, and the read
         * of the node's subscriptions, `GET /wow/ops/subscriptions`.
         */
        var enabled: Boolean = false
    }

    /** The client address and `User-Agent` of each command, recorded as request facts. */
    @InternalWowApi
    class ClientFacts {
        /**
         * Whether the public port records each command's client address and `User-Agent` as the request facts
         * `remote_ip` and `user_agent`, for audit. They are personal data, and written into the event
         * stream, which is never deleted: once recorded, they cannot be erased. On by default; off records
         * neither.
         */
        var enabled: Boolean = true
    }

    /** The problem+json answers of what fails on the application's own routes. */
    @InternalWowApi
    class GlobalErrors {
        /**
         * Whether what fails on the application's own routes is answered as the framework routes' problem+json, so
         * every route answers errors alike: on by default; off leaves those errors to Spring's error handling.
         */
        var enabled: Boolean = true
    }

    @InternalWowApi
    class Error {
        /** The HTTP status the code is answered with, 400 to 599: required, an entry without it refuses the start. */
        var status: Int? = null
    }

    @InternalWowApi
    class Manifest {
        /** Serves `GET /wow/manifest`. */
        var enabled: Boolean = true
    }

    @InternalWowApi
    class OpenApi {
        /** Serves `GET /wow/openapi`, the OpenAPI 3.1 document of the routes. */
        var enabled: Boolean = true

        /** The springdoc bridge, when the application has springdoc. */
        var springdoc: Springdoc = Springdoc()
    }

    /**
     * The springdoc bridge (SpringdocAutoConfiguration): with springdoc on the classpath, the
     * document of the public routes joins springdoc's default document (`/v3/api-docs` and Swagger UI) beside the
     * application's own controllers, and the [group] lists the Wow routes alone.
     */
    @InternalWowApi
    class Springdoc {
        /** Merges the Wow routes into springdoc's documents. */
        var enabled: Boolean = true

        /** The springdoc group of the Wow routes alone: `/v3/api-docs/<group>`. */
        var group: String = SpringdocAutoConfiguration.DEFAULT_GROUP
    }
}

/**
 * One calling service: the [commands] it may call, names or patterns (`AddItem`, `shop.cart.*`, `wow.lifecycle.*`):
 * without them, the domain commands alone; a system command (`wow.*`: the lifecycle, the snapshot rebuild, the
 * schedules) only when an entry naming `wow.` lists it; an entry naming a domain command narrows the domain
 * commands to those listed. The [reads] narrow the models it may read on the internal port's read routes, by
 * `context.aggregate.model` (`ordering.order.snapshot`, `.events`, `.state`) or a dataset's `context.dataset`,
 * patterns with `*`: without them, every model the node serves.
 */
@InternalWowApi
class CallerPermitsProperties {
    /** The commands the service may call, names or patterns; the domain commands alone when empty. */
    var commands: MutableList<String> = ArrayList()

    /** The models the service may read on the internal port's read routes, patterns too; every one when empty. */
    var reads: MutableList<String> = ArrayList()
}

/** How the node calls its peers over HTTP, whichever peer: one HTTP client, these settings. */
@InternalWowApi
class PeerProperties {
    /**
     * How the node finds the internal ports of a peer's nodes: `dns` (the default) looks up the DNS SRV records
     * `wow.peer.dns.name-template` names after the peer's service, as its contract on the class path or its
     * Manifest names it; `static` takes `wow.peers.<context>.url` alone; `spring-cloud`, with wow-spring-cloud on
     * the class path, reads the instances a Spring Cloud service registry has of the service; another value names
     * a community provider's discovery. Each is configured under `wow.peer.<type>` by the options it declares. A
     * peer's `url` wins over any discovery.
     */
    var discovery: String = DnsPeerDiscovery.TYPE

    /**
     * The mode of a peer that names none: `service`, through its internal port as this service, or `user`,
     * through its public routes with the end user's credential.
     */
    var mode: PeerMode = PeerMode.SERVICE

    /**
     * How long a request may take to be answered, a command's longer wait aside; `wow.peers.<context>.timeout`
     * overrides it for one peer.
     */
    var timeout: Duration = WowClient.DEFAULT_TIMEOUT.toJavaDuration()

    /** How long a request may take to connect. */
    var connectTimeout: Duration = HttpCommandCaller.DEFAULT_CONNECT_TIMEOUT.toJavaDuration()

    /** The headers of the public request it serves that a user-mode request forwards. */
    var forwardedHeaders: MutableList<String> = mutableListOf("Authorization")

    /**
     * How a typed client's request that a peer answered with a problem a retry may get past is sent again; a
     * saga's call is not retried here, its subscription's retry budget is the one that counts.
     */
    var retry: PeerRetryProperties = PeerRetryProperties()
}

/**
 * One peer context: its internal port's base [url] (service mode), `http://host:port` of an address in its
 * internal network, never its public port, which refuses calls; its public routes' base [publicUrl] (user mode);
 * the [mode] it is called in, over `wow.peer.mode`; where its [contract] comes from (`jar`, or `manifest` for a
 * peer called by its Manifest alone); and its [timeout], over `wow.peer.timeout`.
 */
@InternalWowApi
class PeerTargetProperties {
    /** The base URL of the peer's internal port (service mode), never its public port; found when not set. */
    var url: URI? = null

    /** The base URL of the peer's public routes (user mode). */
    var publicUrl: URI? = null

    /** The mode the peer is called in: `service` or `user`, over `wow.peer.mode`. */
    var mode: PeerMode? = null

    /** Where the peer's contract comes from: `jar`, its api jar, or `manifest`, its Manifest alone. */
    var contract: Contract = Contract.JAR

    /** How long a call to the peer may take, over `wow.peer.timeout`. */
    var timeout: Duration? = null

    /** Where a peer's contract comes from. */
    @InternalWowApi
    enum class Contract {
        /** Its api jar on the class path: its command objects are sent. */
        JAR,

        /**
         * Its Manifest alone, read from its `GET /wow/manifest`: a console, a gateway or a tool calls it with
         * [me.ahoo.wow.client.NamedCommand]s, without its api jar.
         */
        MANIFEST,
    }
}

/**
 * How a problem a retry may get past is retried ([me.ahoo.wow.client.RetryPolicy]): a 429, a command's problem that
 * keeps the request id, or a connection that could not be made.
 */
@InternalWowApi
class PeerRetryProperties {
    /** The most attempts of a request in all; 1 never retries. */
    var maxAttempts: Int = ClientRetryPolicy.DEFAULT.maxAttempts

    /** The wait before the first retry when the peer gives no `Retry-After`; it doubles at each next one. */
    var initialBackoff: Duration = ClientRetryPolicy.DEFAULT.backoff.toJavaDuration()

    /** The longest `Retry-After` of a peer that is waited out; a peer that asks for longer is not retried. */
    var maxRetryAfter: Duration = ClientRetryPolicy.DEFAULT.maxRetryAfter.toJavaDuration()
}
