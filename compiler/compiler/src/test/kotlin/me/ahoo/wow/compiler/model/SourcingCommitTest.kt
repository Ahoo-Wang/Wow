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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * D377: a sourcing function may take the commit of its event (`EventStream`) second, mutable and immutable states
 * alike; the generated model gives it, deciding and replaying.
 */
@OptIn(ExperimentalCompilerApi::class)
class SourcingCommitTest {
    private val domain = SourceFile.kotlin(
        "Account.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnSourcing
        import me.ahoo.wow.api.decide.DecideScope
        import me.ahoo.wow.api.read.EventStream

        @CreateAggregate
        data class Open(val holder: String)
        data class Deposit(val amount: Int)
        data class Opened(val holder: String, val dueAt: Long)
        data class Deposited(val amount: Int)

        @AggregateRoot
        class Account(private val state: AccountState) {
            fun onCommand(command: Open, scope: DecideScope): Opened = Opened(command.holder, scope.now + 30_000)
            fun onCommand(command: Deposit): Deposited = Deposited(command.amount)
        }

        class AccountState {
            var log = mutableListOf<String>()
            fun onSourcing(event: Opened, commit: EventStream) {
                log.add("opened at " + commit.commitTime + " by " + commit.operator?.id + " due " + event.dueAt)
            }
            @OnSourcing
            fun deposited(event: Deposited, commit: EventStream) {
                log.add("deposited " + event.amount + " at " + commit.commitTime + " v" + commit.version)
            }
        }

        @CreateAggregate
        data class Count(val by: Int)
        data class Counted(val by: Int)

        @AggregateRoot
        class Ledger(private val state: LedgerState) {
            fun onCommand(command: Count): Counted = Counted(command.by)
        }

        data class LedgerState(val total: Int = 0, val lastAt: Long = 0) {
            fun onSourcing(event: Counted, commit: EventStream): LedgerState =
                copy(total = total + event.by, lastAt = commit.commitTime)
        }
        """.trimIndent(),
    )

    @Test
    fun `a sourcing function should read the commit of its event, deciding and replaying`() {
        val scenario = SourceFile.kotlin(
            "Scenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import kotlinx.coroutines.runBlocking
            import me.ahoo.wow.api.caller.Operator
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.kernel.CommandOutcome
            import me.ahoo.wow.kernel.Kernel
            import me.ahoo.wow.kernel.memory.InMemoryEventLog

            fun run(): String = runBlocking {
                val log = InMemoryEventLog()
                var time = 1_000L
                val kernel = Kernel(log, clock = { time })
                val account = accountModel("bank")
                val ref = account.type.ref("tenant", "acc-1")
                kernel.execute(account, Command("c1", "r1", ref, Open("alice"), operator = Operator("alice", "tenant")))
                time = 2_000
                val decided = kernel.execute(account, Command("c2", "r2", ref, Deposit(5))) as CommandOutcome.Committed
                val ledger = ledgerModel("bank")
                val ledgerRef = ledger.type.ref("tenant", "ledger-1")
                time = 3_000
                val counted = kernel.execute(ledger, Command("c3", "r3", ledgerRef, Count(2)))
                    as CommandOutcome.Committed
                // Replayed on a clock far ahead: the commits as recorded.
                time = 9_000
                val replayed = Kernel(log, clock = { time }).stateAt(account, ref, 2)!!.state as AccountState
                val replayedLedger = Kernel(log, clock = { time }).stateAt(ledger, ledgerRef, 1)!!.state
                listOf(
                    (decided.state as AccountState).log,
                    replayed.log,
                    counted.state,
                    replayedLedger,
                ).joinToString("\n")
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", domain, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val account = "[opened at 1000 by alice due 31000, deposited 5 at 2000 v2]"
        val ledger = "LedgerState(total=2, lastAt=3000)"
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null)
            .assert().isEqualTo(listOf(account, account, ledger, ledger).joinToString("\n"))
    }

    @Test
    fun `a sourcing function of a sealed type should read the commit of each of its subclasses`() {
        val source = SourceFile.kotlin(
            "Counter.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Start(val by: Int)
            data class Add(val by: Int)
            sealed interface CounterEvent
            data class Started(val by: Int) : CounterEvent
            data class Added(val by: Int) : CounterEvent

            @AggregateRoot
            class Counter(private val state: CounterState) {
                fun onCommand(command: Start): Started = Started(command.by)
                fun onCommand(command: Add): Added = Added(command.by)
            }

            class CounterState {
                var log = mutableListOf<String>()
                fun onSourcing(event: CounterEvent, commit: me.ahoo.wow.api.read.EventStream) {
                    log.add(event.toString() + " v" + commit.version + " at " + commit.commitTime)
                }
            }
            """.trimIndent(),
        )
        val scenario = SourceFile.kotlin(
            "Scenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import kotlinx.coroutines.runBlocking
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.kernel.Kernel
            import me.ahoo.wow.kernel.memory.InMemoryEventLog

            fun run(): String = runBlocking {
                val log = InMemoryEventLog()
                var time = 1_000L
                val kernel = Kernel(log, clock = { time })
                val model = counterModel("shop")
                val ref = model.type.ref("tenant", "counter-1")
                kernel.execute(model, Command("c1", "r1", ref, Start(1)))
                kernel.execute(model, Command("c2", "r2", ref, Add(2)).also { time = 2_000 })
                (Kernel(log).stateAt(model, ref, 2)!!.state as CounterState).log.toString()
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", source, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null)
            .assert().isEqualTo("[Started(by=1) v1 at 1000, Added(by=2) v2 at 2000]")
    }

    @Test
    fun `a sourcing function's second parameter other than its commit should be a compile error`() {
        val source = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.OnSourcing
            import me.ahoo.wow.api.read.EventStream

            @CreateAggregate
            data class Create(val id: String)
            data class Created(val id: String)
            data class Bumped(val by: Int)
            data class Cleared(val all: Boolean)

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: Create): Created = Created(command.id)
            }

            class CartState {
                fun onSourcing(event: Created, extra: String) {}
                @OnSourcing fun bumped(event: Bumped, commit: EventStream?) {}
                @OnSourcing fun cleared(event: Cleared, commit: EventStream, extra: String) {}
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "sourcing function onSourcing takes kotlin.String second: a sourcing function's second parameter is " +
                "the commit of its event, a me.ahoo.wow.api.read.EventStream",
            "sourcing function bumped takes me.ahoo.wow.api.read.EventStream? second",
            "sourcing function cleared must take its event, and may take its commit " +
                "(me.ahoo.wow.api.read.EventStream) second",
        )
    }
}
