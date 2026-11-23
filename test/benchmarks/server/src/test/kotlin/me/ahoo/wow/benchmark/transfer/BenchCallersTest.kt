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

package me.ahoo.wow.benchmark.transfer

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** The callers of the benchmark's access arms (`benchmark.access`): the policy arm's teller holds its role. */
class BenchCallersTest {
    private fun callerOf(access: String?): Caller? {
        val environment = StandardEnvironment()
        access?.let {
            environment.propertySources.addFirst(MapPropertySource("bench", mapOf("benchmark.access" to it)))
        }
        return runBlocking { BenchCallers().benchCallerResolver(environment).resolve(mockk()) }
    }

    @Test
    fun `the policy arm calls as a teller, the arm without access as the anonymous caller`() {
        callerOf("policy")!!.let {
            it.subject.assert().isEqualTo("bench-teller")
            it.hasRole("teller").assert().isTrue()
        }
        callerOf(null).assert().isSameAs(Caller.ANONYMOUS)
    }
}
