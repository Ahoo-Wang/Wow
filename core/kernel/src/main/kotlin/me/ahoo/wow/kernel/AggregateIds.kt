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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateIdLimits
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace

/**
 * The one rule of which aggregate ids no route or store keeps as they were given, so that every path an id enters by
 * (admission of a caller's or a peer's request, the intake, a saga's derived and a timer's commands, an id a generator
 * gives, the test DSL) and `migrate-data`, which reports the 9.x aggregates that have one before the cut-over, never
 * disagree about an id. An aggregate exists only with an id each of its by-id routes reaches, behind the gateway in
 * front of the service too (#6594).
 */
@InternalWowApi
object AggregateIds {
    /** The longest aggregate id admission takes, in UTF-16 units ([AggregateIdLimits.MAX_LENGTH]). */
    const val MAX_LENGTH: Int = AggregateIdLimits.MAX_LENGTH

    /** The most bytes in UTF-8 of an aggregate id admission takes ([AggregateIdLimits.MAX_BYTES]). */
    const val MAX_BYTES: Int = AggregateIdLimits.MAX_BYTES

    /** The characters a gateway's path check refuses in a path segment, encoded: `/`, `;` and `\`. */
    const val UNCARRIED: String = "/;\\"

    /**
     * Why [id], an aggregate id, is one admission refuses whatever its length in UTF-16 units, as text, or null when it
     * is not:
     *
     * - `.` or `..`, a relative path segment, which a proxy normalizing the path would send as another request;
     * - white space at either end, another aggregate than the one without it, to a reader;
     * - `\u0000` or another control character (`\u0000` to `\u001f`, `\u007f` to `\u009f`), which not every store
     *   keeps;
     * - `/`, `;` or `\` ([UNCARRIED]), which a gateway's path check refuses encoded in a path (CoSec 8 answers
     *   `%2F`, `%3B` and `%5C` with 400), so no by-id route would reach it;
     * - more than [MAX_BYTES] bytes in UTF-8, past the share of the gateway's path a route by id leaves its id
     *   ([AggregateIdLimits]).
     *
     * A blank id is refused where it is read, and an id longer than admission takes ([MAX_LENGTH]) is refused by its
     * length ([refused]): neither is answered here.
     */
    fun unaddressable(id: String?): String? {
        if (id == null || id.isBlank()) return null
        val uncarried = id.firstOrNull { it in UNCARRIED }
        return when {
            id == "." || id == ".." -> "The aggregate id '$id' is a relative path segment, which a proxy may resolve away."
            id.first().isWhitespace() || id.last().isWhitespace() ->
                "The aggregate id '$id' begins or ends with white space, which a reader would not see."

            id.any(Char::isISOControl) ->
                "The aggregate id holds a control character (\\u0000 to \\u001f, \\u007f to \\u009f), which not every " +
                    "store keeps."

            uncarried != null ->
                "The aggregate id '$id' holds '$uncarried', which a gateway's path check refuses in a path, so no route " +
                    "by its id would reach it: an aggregate id holds no '/', ';' or '\\'."

            AggregateIdLimits.utf8Length(id) > MAX_BYTES ->
                "The aggregate id has ${id.length} characters, ${AggregateIdLimits.utf8Length(id)} bytes in UTF-8, " +
                    "more than the $MAX_BYTES bytes a route by it carries behind a gateway: $MAX_BYTES bytes are " +
                    "$MAX_LENGTH characters of most scripts and ${MAX_BYTES / THREE_BYTE} of Chinese, Japanese or Korean."

            else -> null
        }
    }

    /**
     * Why admission refuses [id] as an aggregate id, as text, or null when it takes it: longer than [MAX_LENGTH]
     * UTF-16 units, or [unaddressable]. A blank id is refused where it is read.
     */
    fun refused(id: String?): String? = oversized("aggregate id", id) ?: unaddressable(id)

    /**
     * Why admission refuses a request's ids as malformed (`wow.command.malformed`) before any store sees them, or null
     * when it takes them: its [requestId], [tenantId], and the owner and space of [scope], longer than [MAX_LENGTH]
     * characters, its [aggregateId] [refused] (null before one is known), and the owner or space [body] transfers the
     * aggregate to ([oversizedBody]) when given. The one check of the gateway, of a peer's admission and of the test DSL.
     */
    fun oversized(requestId: String, aggregateId: String?, tenantId: String, scope: Scope, body: Any? = null): String? =
        oversized("request id", requestId) ?: refused(aggregateId)
            ?: oversized("tenant id", tenantId) ?: oversized("owner id", scope.ownerId)
            ?: oversized("space id", scope.spaceId) ?: body?.let(::oversizedBody)

    /** Why the owner or space [body] transfers the aggregate to is too long, or null when it transfers none or fits. */
    fun oversizedBody(body: Any): String? = when (body) {
        is TransferOwner -> oversized("owner id", body.ownerId)
        is TransferSpace -> oversized("space id", body.spaceId)
        else -> null
    }

    /** Why [id], the [name] of a request, is too long; checked one by one, without a list of pairs per command. */
    fun oversized(name: String, id: String?): String? = id?.takeIf { it.length > MAX_LENGTH }
        ?.let { "The $name is at most $MAX_LENGTH characters; this one has ${it.length}." }

    /** The bytes in UTF-8 of a character of Chinese, Japanese or Korean, as of most of the Basic Multilingual Plane. */
    private const val THREE_BYTE = 3
}
