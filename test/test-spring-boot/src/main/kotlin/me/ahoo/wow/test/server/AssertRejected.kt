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

package me.ahoo.wow.test.server

import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.client.CommandRejectedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The rejection the command [send] sends is answered with: it fails, saying so, unless [send] throws a
 * [CommandRejectedException], with the exception it threw instead as its cause.
 *
 * ```kotlin
 * assertRejected { server.commands(alice).wait(Withdraw(150), "alice") }
 *     .code.assert().isEqualTo("bank.account.insufficient-balance")
 * val invalid = assertRejected { server.commands(bob).wait(Deposit(0), "bob") }
 * invalid.violations.single().path.assert().isEqualTo("amount")
 * ```
 *
 * [send] may call the typed clients' `suspend` functions: it runs blocking the calling thread, as a test does, in a
 * coroutine of its own, so an element of a caller's coroutine context (`ForwardedHeaders`) is given in [send] itself.
 */
@Suppress("TooGenericExceptionCaught")
fun assertRejected(send: suspend () -> Unit): CommandRejectedException {
    val failure = try {
        runBlocking { send() }
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (thrown: Throwable) {
        thrown
    }
    return failure as? CommandRejectedException ?: throw AssertionError(
        "Not rejected: the command " +
            (failure?.let { "failed with ${it.javaClass.name}." } ?: "was answered without a rejection."),
        failure,
    )
}
