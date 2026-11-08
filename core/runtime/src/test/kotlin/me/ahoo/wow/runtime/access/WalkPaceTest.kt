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

package me.ahoo.wow.runtime.access

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The access walk's pace: a share of the time by construction, halved while the writes press, at most a rate. */
class WalkPaceTest {
    @Test
    fun `after an aggregate the walk waits so its stamping took at most its share of the time`() {
        val pace = WalkPace(0.1, 1_000_000)
        pace.pause(10.milliseconds).assert().isEqualTo(90.milliseconds)
        WalkPace(0.25, 1_000_000).pause(10.milliseconds).assert().isEqualTo(30.milliseconds)
        // The whole share waits for nothing, unless the rate says so.
        WalkPace(1.0, 1_000_000).pause(10.milliseconds).assert().isEqualTo(Duration.ZERO)
        WalkPace(1.0, 10).pause(10.milliseconds).assert().isEqualTo(90.milliseconds)
        // A share's wait longer than the rate's is the one waited.
        WalkPace(0.1, 50).pause(10.milliseconds).assert().isEqualTo(90.milliseconds)
    }

    @Test
    fun `pressed writes halve the share down to an eighth, and calm ones add back an eighth a period`() {
        val time = TestTimeSource()
        val pace = WalkPace(0.4, 1_000_000, time)
        pace.least.assert().isEqualTo(0.05)
        pace.sense { true }
        pace.share.assert().isEqualTo(0.2)
        // Once a period: a second sense within it reads nothing.
        var read = false
        pace.sense {
            read = true
            true
        }
        read.assert().isFalse()
        pace.share.assert().isEqualTo(0.2)
        repeat(4) {
            time += WalkPace.PERIOD
            pace.sense { true }
        }
        // However long the writes press, the walk keeps an eighth.
        pace.share.assert().isEqualTo(0.05)
        pace.press()
        pace.share.assert().isEqualTo(0.05)
        time += WalkPace.PERIOD
        pace.sense { false }
        pace.share.assert().isEqualTo(0.1)
        repeat(10) {
            time += WalkPace.PERIOD
            pace.sense { false }
        }
        pace.share.assert().isEqualTo(0.4)
        // At the least share the walk still goes on: its stamping takes an eighth of 40%, 5% of the time.
        WalkPace(0.4, 1_000_000).apply { repeat(4) { press() } }.pause(5.milliseconds)
            .assert().isEqualTo(95.milliseconds)
    }

    @Test
    fun `a page holds what the last pace stamps in an interval, from one to the batch`() {
        val pace = WalkPace(0.1, 1_000_000)
        // One aggregate first, whose cost says what the pace is.
        pace.pageOf(100, 5.seconds).assert().isEqualTo(1)
        // 10 ms stamping, 90 ms waiting: 50 in five seconds.
        pace.pause(10.milliseconds)
        pace.pageOf(100, 5.seconds).assert().isEqualTo(50)
        pace.pageOf(20, 5.seconds).assert().isEqualTo(20)
        pace.pause(1.seconds)
        pace.pageOf(100, 5.seconds).assert().isEqualTo(1)
        // A stamping that took no time and no wait says nothing of the pace: a whole batch.
        WalkPace(1.0, Int.MAX_VALUE).apply { pause(Duration.ZERO) }.pageOf(100, 5.seconds).assert().isEqualTo(100)
        pace.toString().assert().isEqualTo("WalkPace(share=0.1, most=0.1, rate=1000000)")
    }

    @Test
    fun `a pace refuses a share it cannot keep`() {
        assertThrows<IllegalArgumentException> { WalkPace(0.0, 1) }
        assertThrows<IllegalArgumentException> { WalkPace(1.1, 1) }
        assertThrows<IllegalArgumentException> { WalkPace(0.1, 0) }
    }
}
