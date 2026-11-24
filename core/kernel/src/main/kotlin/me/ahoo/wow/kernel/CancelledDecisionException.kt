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

import kotlinx.coroutines.CancellationException

/**
 * A decide function or an after hook ([function]) of the command [command] ended with a cancellation of its own
 * ([cause]) while its caller still waited: a call it bounded with `withTimeout` that did not answer in time, or a
 * scope of its own that was cancelled. It is a failure of the command's handling, as a remote call's timeout is, never
 * its caller's cancellation, which the kernel lets through as it is: the caller is answered that the command failed,
 * and a subscription retries it, then parks it, as any failure.
 */
internal class CancelledDecisionException(command: Class<*>, function: String, cause: CancellationException) :
    RuntimeException(
        "The $function of ${command.simpleName} was cancelled by a cancellation of its own while its caller waits: " +
            "${cause.message}. A call it bounds with withTimeout that does not answer in time fails the command; " +
            "catch the TimeoutCancellationException to answer otherwise.",
        cause,
    )
