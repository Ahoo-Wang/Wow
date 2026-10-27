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

package me.ahoo.wow.api.subscribe

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult

/**
 * The target of a synchronous call ([SagaContext.call]) refused [command]: a business outcome, its
 * [result] REJECTED with the target's error code. Calling again derives the same request, which the target refuses
 * again, so it is not retried: a handler that does not catch it parks its aggregate at once, as a derived command's
 * rejection without an `@OnRejected` handler does, and a wait at the saga's hop ends REJECTED.
 */
class CallRejectedException(val command: Command<*>, val result: CommandResult) :
    RuntimeException(
        "The call of ${command.id} on ${command.ref} was rejected: ${result.error?.code} ${result.error?.message}",
    ) {
    init {
        require(result.status == CommandResult.Status.REJECTED) { "A rejected call has a REJECTED result." }
    }

    /** The target's error code. */
    val code: String get() = result.error!!.code
}

/**
 * A synchronous call ([SagaContext.call]) did not reach a result: the target is down or unreachable,
 * did not answer in time, answered FAILED ([result], such as a store it could not reach), or refused the service's
 * credential. A technical failure: the handler fails, and its subscription retries it within its budget,
 * then parks the aggregate; every retry calls with the same request, so the target decides it once.
 */
class CallFailedException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
    val result: CommandResult? = null,
) : RuntimeException(message, cause)
