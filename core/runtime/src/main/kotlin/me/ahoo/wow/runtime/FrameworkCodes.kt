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

package me.ahoo.wow.runtime

import me.ahoo.wow.kernel.KernelRejections
import me.ahoo.wow.runtime.wait.ResultCodes

/**
 * Every error code the framework itself answers with (design 05 §3; decisions D60, D103), in one place for the edge to
 * map to its statuses: the admission codes defined here, the kernel's rejections ([KernelRejections]) and the
 * runtime's results ([ResultCodes]). A domain's own codes are not among them.
 *
 * The Manifest's error table lists the same codes for clients; until the two are generated from one source, a code
 * added here is added there too.
 */
object FrameworkCodes {
    /** The request carries no credentials the edge accepts (HTTP 401). The edge answers it; admission never does. */
    const val UNAUTHENTICATED = "wow.auth.unauthenticated"

    /**
     * The caller may not do this (decision D95): an authorizer denied the action, or the request asked for another
     * scope (`Wow-Scope`) without the permission to act across scopes.
     */
    const val AUTH_FORBIDDEN = "wow.auth.forbidden"

    /**
     * The request cannot be read as the command it names: its body does not decode as the command type, or it gives
     * an identity fact twice or not at all (decision D92).
     */
    const val MALFORMED = "wow.command.malformed"

    /** The command's body breaks a validation rule; the error lists the violations (decision D106). */
    const val INVALID = "wow.command.invalid"

    const val UNHANDLED = KernelRejections.UNHANDLED
    const val AGGREGATE_FORBIDDEN = KernelRejections.FORBIDDEN
    const val NOT_FOUND = KernelRejections.NOT_FOUND
    const val EXISTS = KernelRejections.EXISTS
    const val VERSION_MISMATCH = KernelRejections.VERSION_MISMATCH
    const val REQUEST_ID_REUSED = KernelRejections.REQUEST_ID_REUSED
    const val DELETED = KernelRejections.DELETED
    const val CONTENDED = ResultCodes.CONTENDED
    const val UNKNOWN = ResultCodes.UNKNOWN
    const val BUSY = ResultCodes.BUSY
    const val NODE_CLOSING = ResultCodes.NODE_CLOSING
    const val STORAGE_UNAVAILABLE = ResultCodes.STORAGE_UNAVAILABLE
    const val WAIT_INVALID = ResultCodes.WAIT_INVALID
    const val WAIT_TIMEOUT = ResultCodes.WAIT_TIMEOUT
    const val SUBSCRIPTION_PARKED = ResultCodes.SUBSCRIPTION_PARKED

    /** All of them. */
    val ALL: Set<String> = setOf(
        UNAUTHENTICATED,
        AUTH_FORBIDDEN,
        MALFORMED,
        INVALID,
        UNHANDLED,
        AGGREGATE_FORBIDDEN,
        NOT_FOUND,
        EXISTS,
        VERSION_MISMATCH,
        REQUEST_ID_REUSED,
        DELETED,
        CONTENDED,
        UNKNOWN,
        BUSY,
        NODE_CLOSING,
        STORAGE_UNAVAILABLE,
        WAIT_INVALID,
        WAIT_TIMEOUT,
        SUBSCRIPTION_PARKED,
    )
}
