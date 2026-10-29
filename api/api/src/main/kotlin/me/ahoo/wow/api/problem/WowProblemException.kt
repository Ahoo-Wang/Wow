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

package me.ahoo.wow.api.problem

import me.ahoo.wow.api.result.CommandResult
import kotlin.time.Duration

/**
 * A request Wow did not carry out for a reason of its code: the [code] (`wow.command.busy`, `wow.storage.unavailable`,
 * `wow.query.invalid`, ...), the HTTP [status] it is answered with (problem+json), the command's [result] when it had
 * one, and what a retry may do ([retry], after [retryAfter]), as the error catalog says of the code. The same failure is
 * the same exception in process and over HTTP: each boundary maps its own errors to it once.
 */
class WowProblemException @JvmOverloads constructor(
    val code: String,
    val status: Int,
    val retry: Retry,
    val retryAfter: Duration?,
    message: String,
    val result: CommandResult? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Whether a retry may get past it ([Retry.NONE] is not). */
    val retryable: Boolean get() = retry != Retry.NONE
}

/**
 * What a retry of a request answered with an error may do, as the error catalog declares it for the error's code and
 * every problem carries it (`retry`, with `Retry-After` when a retry may get past it). On the wire, lower-kebab:
 * `none`, `same-request`, `new-request`.
 */
enum class Retry {
    /** A retry fails alike: the request, or the state it meets, must change first. */
    NONE,

    /**
     * Send it again with the same request id (`Wow-Request-Id`): a command that did commit is then answered as the
     * duplicate it is, so a retry never commits it twice.
     */
    SAME_REQUEST,

    /** Send it again as it is, a new request: nothing was done that a retry could repeat. */
    NEW_REQUEST,
}
