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

import me.ahoo.wow.api.annotation.InternalWowApi
import java.net.URI
import java.net.URISyntaxException

/**
 * A node's internal address as its members read it ([me.ahoo.wow.spi.subscription.Member.address]) and the internal
 * port's clients join it into a URI: `host:port`, an IPv6 literal in brackets (RFC 3986), `[::1]:9090`, so that a
 * colon of the host is never read as the port's. The one place an address is built from a host and a port, or its
 * host read back; everywhere else an address is compared as it is.
 */
@InternalWowApi
object NodeAddress {
    /** [host] and [port] as an address: `host:port`, `[ipv6]:port`. A host already in brackets stays as it is. */
    fun of(host: String, port: Int): String = "${literal(host)}:$port"

    /** The host of [address], `host:port` or `[ipv6]:port`, without the brackets of an IPv6 literal. */
    fun hostOf(address: String): String =
        if (address.startsWith("[")) {
            address.substringAfter('[').substringBefore(']')
        } else {
            address.substringBeforeLast(':')
        }

    /**
     * Whether [address] is one a URI takes as its authority, as the internal port's clients join it: not a bare IPv6
     * literal (`::1:9090`, whose colons cannot be told from the port's), which [of] writes as `[::1]:9090`.
     */
    fun isValid(address: String): Boolean = try {
        URI("http://$address").host != null
    } catch (_: URISyntaxException) {
        false
    }

    /** [host] as a URI writes it: an IPv6 literal in brackets. */
    private fun literal(host: String): String = if (':' in host && !host.startsWith("[")) "[$host]" else host
}
