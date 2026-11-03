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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class EventuallyTest {
    @Test
    fun `a condition that holds should return at once`() {
        var polls = 0
        eventually("held") { ++polls > 0 }
        polls.assert().isEqualTo(1)
    }

    @Test
    fun `a condition should be polled until it holds, what it throws meanwhile not holding`() {
        var polls = 0
        eventually("the third poll", poll = 1.milliseconds) {
            polls++
            check(polls > 1) { "not yet" }
            polls == 3
        }
        polls.assert().isEqualTo(3)
    }

    @Test
    fun `an assertion should not hold until it passes`() {
        var polls = 0
        eventually("the assertion", poll = 1.milliseconds) {
            (++polls).assert().isEqualTo(2)
            true
        }
        polls.assert().isEqualTo(2)
    }

    @Test
    fun `a condition that never holds should fail saying what, with the last failure`() {
        val failure = assertThrows<AssertionError> {
            eventually("the impossible", timeout = 30.milliseconds, poll = 5.milliseconds) { error("never") }
        }
        failure.message.assert().isEqualTo("Not reached in 30ms: the impossible.")
        failure.cause!!.message.assert().isEqualTo("never")
        val never = assertThrows<AssertionError> { eventually("false", timeout = 10.milliseconds) { false } }
        never.cause.assert().isNull()
    }

    @Test
    fun `a suspending condition should run in the coroutine`() {
        var polls = 0
        runBlocking {
            eventually("after a delay", poll = 1.milliseconds) {
                delay(1)
                ++polls == 2
            }
        }
        polls.assert().isEqualTo(2)
    }

    @Test
    fun `a cancellation should not be waited out`() {
        assertThrows<CancellationException> { eventually("cancelled") { throw CancellationException("stop") } }
    }

    @Test
    fun `the timeout and the poll should be positive`() {
        assertThrows<IllegalArgumentException> { eventually("now", timeout = Duration.ZERO) { true } }
        assertThrows<IllegalArgumentException> { eventually("now", poll = Duration.ZERO) { true } }
    }
}
