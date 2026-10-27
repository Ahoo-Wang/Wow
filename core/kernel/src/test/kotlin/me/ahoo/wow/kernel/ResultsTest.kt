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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.decide.Decision
import org.junit.jupiter.api.Test

class ResultsTest {
    @Test
    fun `every return shape becomes a decision`() {
        decisionOf(Decision.reject("c", "m")).assert().isEqualTo(Decision.reject("c", "m"))
        decisionOf(Unit).assert().isEqualTo(Decision.NoOp)
        decisionOf(null).assert().isEqualTo(Decision.NoOp)
        // A nullable list of events, null, is a no-op too.
        decisionOf(null as List<Any>?).assert().isEqualTo(Decision.NoOp)
        decisionOf(emptyList<Any>()).assert().isEqualTo(Decision.NoOp)
        decisionOf(arrayOf<Any>()).assert().isEqualTo(Decision.NoOp)
        decisionOf("event").assert().isEqualTo(Decision.events("event"))
        decisionOf(listOf("a", "b")).assert().isEqualTo(Decision.events("a", "b"))
        decisionOf(arrayOf("a", "b")).assert().isEqualTo(Decision.events("a", "b"))
    }

    @Test
    fun `an after hook returns events only`() {
        eventsOf(listOf("a")).assert().containsExactly("a")
        eventsOf(Unit).assert().isEmpty()
        assertThrownBy<IllegalStateException> { eventsOf(Decision.NoOp) }
        assertThrownBy<IllegalArgumentException> { eventsOf(listOf("a", null)) }
    }
}
