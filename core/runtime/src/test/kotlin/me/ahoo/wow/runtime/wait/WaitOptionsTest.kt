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

package me.ahoo.wow.runtime.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/** The bounds a wait's options hold, and Wow's own copy of them. */
class WaitOptionsTest {
    @Test
    fun `the maximum timeout is positive, a chain has a hop, and a copy keeps the hops it is not given`() {
        assertThrows<IllegalArgumentException> { WaitOptions(maxTimeout = 0.seconds) }
        assertThrows<IllegalArgumentException> { WaitOptions(maxHops = 0) }
        WaitOptions(maxHops = 3).copyWith(maxRegistered = 1).maxHops.assert().isEqualTo(3)
    }
}
