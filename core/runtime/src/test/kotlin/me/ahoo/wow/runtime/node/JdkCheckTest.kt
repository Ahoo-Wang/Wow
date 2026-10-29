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

package me.ahoo.wow.runtime.node

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class JdkCheckTest {
    private val warnings = mutableListOf<String>()

    private fun check(feature: Int) = JdkCheck({ feature }, warnings::add)

    @Test
    fun `a JDK that pins virtual threads should be warned about once, naming the risk and the recommendation`() {
        val check = check(21)
        check.warnOnce().assert().isTrue()
        check.warnOnce().assert().isFalse()
        warnings.assert().hasSize(1)
        warnings.single().assert()
            .contains("JDK 21")
            .contains("synchronized")
            .contains("liveness")
            .contains("Run Wow on JDK 25")
    }

    @Test
    fun `the last JDK that pins virtual threads should still be warned about`() {
        check(23).warnOnce().assert().isTrue()
    }

    @Test
    fun `a JDK that unmounts blocked virtual threads should not be warned about`() {
        check(JdkCheck.UNPINNED_FROM).warnOnce().assert().isFalse()
        check(JdkCheck.RECOMMENDED).warnOnce().assert().isFalse()
        warnings.assert().isEmpty()
    }
}
