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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** A rejection that carries events: checked as a rejection, its events as any committed ones. */
class RejectionEventsDslTest {
    data class Open(val owner: String)
    data class Unlock(val code: String)
    data class Opened(val owner: String)
    data class Unlocked(val code: String)
    data class AttemptFailed(val code: String)

    class Safe(val id: String) {
        var failures = 0
        var unlocked = false
    }

    private val model = aggregate<Safe>("vault", "safe") {
        initial { Safe(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body ->
            if (body.owner.isBlank()) {
                Decision.reject("no-owner", "A safe has an owner.", events = listOf(AttemptFailed("")))
            } else {
                Decision.events(Opened(body.owner))
            }
        }
        decide<Unlock> { _, body ->
            if (body.code == SECRET) {
                Decision.events(Unlocked(body.code))
            } else {
                Decision.reject("wrong-code", "Wrong code.", events = listOf(AttemptFailed(body.code)))
            }
        }
        evolve<Opened> { }
        evolve<Unlocked> { unlocked = true }
        evolve<AttemptFailed> { failures++ }
    }

    @Test
    fun `a rejection's events should be checked beside the rejection, and the state they left`() =
        aggregateSpec(model) {
            given(Opened("alice"))
            whenCommand(Unlock("0000")) {
                expectRejected("wrong-code") { message.assert().isEqualTo("Wrong code.") }
                expectEvents(AttemptFailed("0000"))
                expectEventTypes(AttemptFailed::class)
                expectEvent<AttemptFailed> { code.assert().isEqualTo("0000") }
                expectVersion(2)
                expectState { failures.assert().isEqualTo(1) }
            }
            // The next command decides on the state the rejection's events left.
            whenCommand(Unlock("1111")) {
                expectRejected("wrong-code")
                expectState { failures.assert().isEqualTo(2) }
            }
        }

    @Test
    fun `a rejection with events should fail a scenario that does not check it, or expects none`() {
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                given(Opened("alice"))
                whenCommand(Unlock("0000")) { expectEventTypes(AttemptFailed::class) }
            }
        }.hasMessageEndingWith(
            "The command was rejected vault.safe.wrong-code: Wrong code, committing [AttemptFailed(code=0000)] at v2, " +
                "which the scenario does not check: expectRejected checks a rejection.",
        )
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                given(Opened("alice"))
                whenCommand(Unlock("0000")) { expectNoError() }
            }
        }.hasMessageContaining("Expected no error but it was rejected vault.safe.wrong-code")
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                given(Opened("alice"))
                whenCommand(Unlock("0000")) { expectRejected("locked") }
            }
        }.hasMessageContaining("Expected the rejection vault.safe.locked but it was rejected vault.safe.wrong-code")
        assertThrownBy<AssertionError> {
            aggregateSpec(model) { givenCommands(Open("alice"), Unlock("0000")) }
        }.hasMessage(
            "givenCommands(Unlock) (command-2): Expected it to commit but it was rejected vault.safe.wrong-code: " +
                "Wrong code, committing [AttemptFailed(code=0000)] at v2.",
        )
    }

    @Test
    fun `a creation rejected with events should fail the specification, naming the command`() {
        assertThrownBy<AssertionError> {
            aggregateSpec(model) { whenCommand(Open(" ")) { expectRejected("no-owner") } }
        }.hasMessageStartingWith("whenCommand(Open) (command-2)")
            .hasMessageContaining(
                "The creation command-2 (vault.safe.Open) was rejected with events, which are dropped"
            )
    }

    private companion object {
        const val SECRET = "4242"
    }
}
