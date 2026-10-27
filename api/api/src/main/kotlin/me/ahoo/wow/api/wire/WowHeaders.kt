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

package me.ahoo.wow.api.wire

/**
 * The request headers of the public port. A request header that is neither one of these
 * nor a request fact the host lists (`wow.http.request-facts`) is ignored, but for `User-Agent`, which the public
 * port records as the default request fact `user_agent` beside the client's address, `remote_ip`.
 */
object WowHeaders {
    /** The caller's id of the request, kept as the command's request id: a retry with the same one is deduplicated. */
    const val REQUEST_ID: String = "Wow-Request-Id"

    /** The wait plan, in the grammar of [WaitHeader]. */
    const val WAIT: String = "Wow-Wait"

    /** How long to wait: `500ms`, `10s` or an ISO-8601 duration (`PT10S`). */
    const val WAIT_TIMEOUT: String = "Wow-Wait-Timeout"

    /** The scope to act in, for a caller allowed to act across scopes: an RFC 8941 dictionary. */
    const val SCOPE: String = "Wow-Scope"

    /** The space, among the caller's own, to act in; the caller resolver checks the membership. */
    const val SPACE: String = "Wow-Space"

    /**
     * The root command a service's synchronous call acts for, in the form of
     * [CallHeader]: taken on the internal port alone, from a service that names itself in [SERVICE] (the call
     * routes of `wow-http`); refused (403) on the public port whatever else the request carries.
     */
    const val CALL: String = "Wow-Call"

    /**
     * The service a request to the internal port comes from, by name: every node states its own,
     * `spring.application.name`, on its forwards, wait signals, synchronous calls and peer reads. It is a statement,
     * not a credential: the internal port is reachable by the platform's own pods alone (a NetworkPolicy), and the
     * name serves to catch a misconfiguration, such as a call a service does not list or a forward to another
     * service's node. The public port never reads it.
     */
    const val SERVICE: String = "Wow-Service"

    /** The expected version of the aggregate, as an entity tag: `If-Match: "3"`. */
    const val IF_MATCH: String = "If-Match"

    /** The version of a state read, as an entity tag: `ETag: "3"`. */
    const val ETAG: String = "ETag"

    /**
     * On the answer of a snapshot query or its descriptor, `true` while the aggregate type's snapshots are being
     * rebuilt or backfilled: the query answers from them as they are, some not yet rewritten.
     */
    const val REBUILDING: String = "Wow-Rebuilding"
}
