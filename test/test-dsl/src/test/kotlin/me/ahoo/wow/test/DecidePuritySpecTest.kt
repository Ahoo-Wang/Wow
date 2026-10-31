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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** A spec fails a decide function that changes the state, whatever it decides. */
class DecidePuritySpecTest {
    data class Fund(val amount: Int)
    data class Spend(val amount: Int)
    data class Funded(val amount: Int)
    data class Spent(val amount: Int)

    /** Mutable on purpose: a decide function can change it, which only its events may. */
    class Wallet(val id: String) {
        var balance = 0
    }

    private val model = aggregate<Wallet>("bank", "wallet") {
        initial { Wallet(it.id) }
        decide<Fund>(Creation.REQUIRED) { _, body -> Decision.events(Funded(body.amount)) }
        decide<Spend> { state, body ->
            // The defect under test: the balance is spent before the decision is known.
            state.balance -= body.amount
            if (state.balance < 0) Decision.reject("short", "Not enough.") else Decision.events(Spent(body.amount))
        }
        evolve<Funded> { balance += it.amount }
        evolve<Spent> { balance -= it.amount }
    }

    @Test
    fun `a decide function that changes the state and then rejects, writing nothing, should fail the spec`() {
        val failure = runCatching {
            aggregateSpec(model) {
                given(Funded(10))
                whenCommand(Spend(20)) { expectRejected("short") }
            }
        }.exceptionOrNull()
        failure.assert().isNotNull()
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            .assert().contains("changed the state")
    }

    @Test
    fun `a decide function that changes the state and then decides events should fail the spec`() {
        val failure = runCatching {
            aggregateSpec(model) {
                given(Funded(10))
                whenCommand(Spend(5)) { expectEvents(Spent(5)) }
            }
        }.exceptionOrNull()
        failure.assert().isNotNull()
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            .assert().contains("changed the state")
    }
}
