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

package me.ahoo.wow.command

import jakarta.validation.Validator
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.validation.validateCommand
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitHandle
import me.ahoo.wow.command.wait.WaitPlan
import reactor.core.publisher.Mono

/**
 * The gateway's one admission chain, in a fixed order: validate the body, check the request ID, then (for a wait)
 * register the wait handle and build the message that carries the wait headers.
 *
 * An invalid command does not reserve its request ID, and no wait handle is registered for a command that was not
 * admitted. The message the gateway sends is complete when it is built: the wait headers are written into a copy of
 * the caller's message, never into the caller's message after the fact.
 */
internal class CommandAdmission(
    private val commandWaitEndpoint: CommandWaitEndpoint,
    private val validator: Validator,
    private val requestIdChecker: RequestIdChecker,
) {
    /**
     * Validates [command], then checks its request ID.
     *
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     * @throws DuplicateRequestIdException if the request ID was already used.
     */
    fun admit(command: CommandMessage<*>): Mono<Void> =
        Mono.fromRunnable<Void> { validate(command.body) }
            .then(checkRequestId(command))

    /**
     * Registers the wait handle of an admitted command and builds the message to send: a copy of [command] whose
     * header also carries the wait headers of [waitPlan].
     */
    fun <H : WaitHandle> registerWait(
        command: CommandMessage<*>,
        waitPlan: WaitPlan,
        register: (WaitPlan) -> H,
    ): AdmittedWait<H> {
        val handle = register(waitPlan)
        val message = command.copy()
        waitPlan.propagate(commandWaitEndpoint, message.header)
        return AdmittedWait(message, handle)
    }

    /**
     * Gives back the request-ID reservation of an admitted [command] that could not be sent, so that sending it
     * again is not taken for a duplicate.
     */
    fun release(command: CommandMessage<*>) {
        requestIdChecker.release(command.aggregateId, command.requestId)
    }

    private fun validate(body: Any) {
        if (body is CommandValidator) {
            body.validate()
        }
        validator.validateCommand(body)
    }

    private fun checkRequestId(command: CommandMessage<*>): Mono<Void> =
        Mono.defer { requestIdChecker.check(command.aggregateId, command.requestId) }
            .flatMap { passed ->
                if (passed) {
                    Mono.empty()
                } else {
                    Mono.error(DuplicateRequestIdException(command.aggregateId, command.requestId))
                }
            }
}

/** An admitted command waited on: the message to send (with its wait headers) and the registered handle. */
internal class AdmittedWait<H : WaitHandle>(
    val message: CommandMessage<*>,
    val handle: H,
)
