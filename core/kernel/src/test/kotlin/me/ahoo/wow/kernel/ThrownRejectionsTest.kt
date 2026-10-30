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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.decide.RejectedException
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.minutes

/** What a decide function throws: a refusal answered as the rejection it stands for, or a failure (D311, D314). */
class ThrownRejectionsTest {
    data class Open(val by: String)
    data class Throw(val label: String)
    data class Opened(val by: String)

    class Account(val id: String)

    /** A library's own exception that happens to extend IllegalStateException: not a refusal. */
    class LibraryStateException(message: String) : IllegalStateException(message)

    private val throwing = mutableMapOf<String, () -> Nothing>()
    private val appended = mutableListOf<Any>()
    private val compensated = mutableListOf<String>()

    private val model = aggregate<Account>("bank", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        decideSuspending<Throw> { _, body ->
            onAppendFailure { compensated += body.label }
            when (body.label) {
                "scope" -> reject("insufficient-funds", "Only 5 left.")
                "timeout" -> withTimeout(1) { delay(1_000) }
                "blank schedule key" -> schedule(" ", ScheduleRule.after(1.minutes), Open("x"))
                "blank cancelled key" -> cancelSchedule(" ")
                else -> throwing.getValue(body.label)()
            }
            Decision.events(Opened(body.label))
        }
        evolve<Opened> { appended += it }
    }

    private val ref = AggregateType("bank", "account").ref("t", "a-1")
    private var sequence = 0

    private fun command(body: Any) = Command("c-${++sequence}", "r-$sequence", ref, body)

    private val remote = CommandRejectedException(
        mockk<CommandResult>(relaxed = true) {
            every { status } returns CommandResult.Status.REJECTED
            every { error } returns ErrorInfo("stock.item.out-of-stock", "None left.")
        },
    )

    /** Each exception kind and the rejection code it is answered with; null for a failure, which is thrown. */
    private val cases: List<Triple<String, () -> Nothing, String?>> = listOf(
        Triple(
            "RejectedException",
            { throw RejectedException(Rejection.of("frozen", "The account is frozen.")) },
            "bank.account.frozen"
        ),
        Triple(
            "require",
            {
                require(false) { "The amount must be positive." }
                error("unreachable")
            },
            FrameworkCodes.COMMAND_INVALID
        ),
        Triple("requireNotNull", { requireNotNull(null) { "No currency." } }, FrameworkCodes.COMMAND_INVALID),
        Triple(
            "check",
            {
                check(false) { "The account is closed." }
                error("unreachable")
            },
            FrameworkCodes.COMMAND_REFUSED
        ),
        Triple("error", { error("The account is closed.") }, FrameworkCodes.COMMAND_REFUSED),
        Triple("remote rejection", { throw remote }, "stock.item.out-of-stock"),
        Triple("IllegalStateException subclass", { throw LibraryStateException("pool exhausted") }, null),
        Triple("NumberFormatException", { throw NumberFormatException("x") }, null),
        Triple("IOException", { throw IOException("connection reset") }, null),
        Triple("UnsupportedOperationException", { throw UnsupportedOperationException("no") }, null),
        // The kernel's own guards inside the scope are faults, not refusals, as a Wow API's misuse is.
        Triple("blank schedule key", { error("unreachable") }, null),
        Triple("blank cancelled key", { error("unreachable") }, null),
        Triple("a Wow API's argument misuse", { throw Misuse.Argument("No aggregate has a state of X.") }, null),
        Triple("a Wow API's state misuse", { throw Misuse.State("No URL calls context x.") }, null),
    )

    @TestFactory
    fun `every kind of thrown exception should be answered as a refusal or a failure`(): List<DynamicTest> =
        cases.map { (label, throwIt, code) ->
            DynamicTest.dynamicTest(label) {
                runTest {
                    throwing[label] = throwIt
                    val log = InMemoryEventLog()
                    Kernel(log).execute(model, command(Open("me")))
                    appended.clear()
                    val result = runCatching { Kernel(log).execute(model, command(Throw(label))) }
                    if (code == null) {
                        result.exceptionOrNull().assert().isNotNull()
                    } else {
                        val rejected = result.getOrThrow() as CommandOutcome.Rejected
                        rejected.rejection.code.assert().isEqualTo(code)
                    }
                    // Refused or failed, nothing is appended, and the attempt's compensations run.
                    appended.filterNot { it == Opened("me") }.assert().isEmpty()
                    compensated.assert().contains(label)
                }
            }
        }

    @Test
    fun `DecideScope reject should be answered as the rejection it names`() = runTest {
        val log = InMemoryEventLog()
        Kernel(log).execute(model, command(Open("me")))
        val rejected = Kernel(log).execute(model, command(Throw("scope"))) as CommandOutcome.Rejected
        rejected.rejection.code.assert().isEqualTo("bank.account.insufficient-funds")
        rejected.rejection.message.assert().isEqualTo("Only 5 left.")
    }

    @Test
    fun `a cancellation inside a decide function should stay a cancellation`() = runTest {
        throwing["cancel"] = { throw CancellationException("cancelled") }
        val log = InMemoryEventLog()
        Kernel(log).execute(model, command(Open("me")))
        runCatching { Kernel(log).execute(model, command(Throw("cancel"))) }.exceptionOrNull()
            .assert().isInstanceOf(CancellationException::class.java)
        runCatching { Kernel(log).execute(model, command(Throw("timeout"))) }.exceptionOrNull()
            .assert().isInstanceOf(TimeoutCancellationException::class.java)
        ThrownRejections.rejectionOf(CancellationException("x")).assert().isNull()
    }

    @Test
    fun `a refusal without a message, or a remote rejection without an error, should still be classified`() {
        ThrownRejections.rejectionOf(IllegalArgumentException())!!.message.assert().isEmpty()
        ThrownRejections.rejectionOf(IllegalStateException())!!.message.assert().isEmpty()
        val bare = CommandRejectedException(
            mockk<CommandResult>(relaxed = true) {
                every { status } returns CommandResult.Status.REJECTED
                every { error } returns null
            },
        )
        ThrownRejections.rejectionOf(bare).assert().isNull()
    }

    @Test
    fun `a thrown rejection should carry no stack trace and its message`() {
        val thrown = RejectedException(Rejection.of("frozen", "The account is frozen."))
        thrown.stackTrace.assert().isEmpty()
        thrown.message.assert().isEqualTo("The account is frozen.")
    }
}
