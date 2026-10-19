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

package me.ahoo.wow.api.decide

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The standard request facts: the keys of a [Command]'s [headers][Command.headers] the framework itself fills, beside
 * those the host lists (decisions D49, D182), written into the committed event stream with them (decision D56). No
 * request header sets one (the trust boundary of decision D226): the edge records each from what it knows.
 *
 * - [APP_ID], the calling application, the [app][me.ahoo.wow.api.caller.Caller.app] of the caller its resolver
 *   authenticated (decision D232); none for a caller without one.
 * - [REMOTE_IP] and [USER_AGENT], the client's address and `User-Agent`, on the public port alone (09 §17 G-10).
 *
 * A command a server derives (a saga's, an `@OnRejected` or `@OnCommitted` handler's compensation, a timer's,
 * decision D199) has no request of its own: it inherits the facts of [PROPAGATED] from the event stream it derives
 * from, and only those (decision D232), as 9.x propagated the application.
 */
object RequestFacts {
    /** The calling application, authenticated by the caller's resolver (decision D232). */
    const val APP_ID: String = "app_id"

    /** The client's address, as the server knows it (G-10). */
    const val REMOTE_IP: String = "remote_ip"

    /** The client's `User-Agent` (G-10). */
    const val USER_AGENT: String = "user_agent"

    /** The facts the framework fills, which no request header sets. */
    @JvmField
    val STANDARD: Set<String> = setOf(APP_ID, REMOTE_IP, USER_AGENT)

    /**
     * The facts a derived command inherits from the event stream it derives from (decision D232): what the request
     * was made for, not where it came from, so [REMOTE_IP] and [USER_AGENT] stay with the request that had them.
     */
    @JvmField
    val PROPAGATED: Set<String> = setOf(APP_ID)

    /** The facts of [headers] a command derived from them inherits ([PROPAGATED]). */
    @InternalWowApi
    @JvmStatic
    fun propagatedOf(headers: Map<String, String>): Map<String, String> =
        if (headers.isEmpty()) headers else headers.filterKeys { it in PROPAGATED }
}
