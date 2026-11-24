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

/**
 * A port of the kernel ([port]: the reservation store, the id generators) that a decide function reached through its
 * scope failed with [cause], an exception its own refusals would be taken for ([ThrownRejections.isRefusal]): a plain
 * `IllegalStateException` of a closed client, a plain `IllegalArgumentException` of a store's own checks. It is a failure
 * of the command, never the caller's refusal: the caller is answered that the command failed, and a subscription
 * retries it, then parks it, as any failure. A failure of the port that no refusal is taken for, such as a store that is
 * unavailable or busy, goes through as it is, with its own answer.
 */
internal class PortFailureException(port: String, cause: Throwable) :
    RuntimeException("The $port failed while deciding: $cause", cause)

/**
 * [block], a call of the kernel's [port] under a decide function, whose failures a refusal would be taken for are a
 * [PortFailureException]; every other failure, and a cancellation, goes through as it is.
 */
@Suppress("TooGenericExceptionCaught") // Only what the refusal mapping would take is wrapped; the rest is rethrown.
internal inline fun <T> guarded(port: String, block: () -> T): T = try {
    block()
} catch (failure: Exception) {
    // A cancellation is never a refusal ([ThrownRejections.isRefusal]): it is rethrown as it is.
    throw if (ThrownRejections.isRefusal(failure)) PortFailureException(port, failure) else failure
}
