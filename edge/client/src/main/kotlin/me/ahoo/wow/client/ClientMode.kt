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

/**
 * How a client calls a peer, and as whom (decisions D193, D232, D236, D259):
 *
 * - [Service]: on the peer's internal port ([Port.INTERNAL]), as this service, which it names in `Wow-Service`
 *   (decision D300), asserting the caller it acts for as the root context of `Wow-Call`: its tenant, scope, operator
 *   and the request facts that propagate (the application, `app_id`). The peer takes that assertion from a service
 *   that names itself alone, its internal port being reachable by the platform's own pods only,
 *   and lets it call the commands and read the models its `wow.callers.<service>` entries permit. A command is
 *   answered once committed, whatever its wait plan: the internal port does not stream.
 * - [User]: on the peer's public routes ([Port.PUBLIC]), forwarding the end user's credential ([UserCredential]): the
 *   peer authenticates the user as its own edge would, and the caller a client was given ([me.ahoo.wow.api.client.
 *   CommandClient.of]) only chooses which credential to forward. Waits and streams are the public port's.
 */
sealed interface ClientMode {
    /** The port of the peer this mode calls. */
    val port: Port

    /**
     * Calls the internal port as the service named [service], asserting the root context ([ClientMode]); a client
     * that names no service is refused by every peer.
     */
    class Service(val service: String?) : ClientMode {
        override val port: Port get() = Port.INTERNAL

        override fun toString(): String = "ClientMode.Service"
    }

    /** Calls the public routes, forwarding the end user's credential ([ClientMode]). */
    class User(val credential: UserCredential) : ClientMode {
        override val port: Port get() = Port.PUBLIC

        override fun toString(): String = "ClientMode.User"
    }
}
