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

package me.ahoo.wow.http.problem

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ProblemValueTest {
    @Test
    fun `a problem is made by of, equal by its fields, and copied`() {
        val problem = Problem.of(503, "Unavailable", "down", "wow.storage.unavailable")
        problem.assert().isEqualTo(problem).isEqualTo(Problem.of(503, "Unavailable", "down", "wow.storage.unavailable"))
        problem.hashCode().assert().isEqualTo(
            Problem.of(503, "Unavailable", "down", "wow.storage.unavailable").hashCode()
        )
        problem.copyWith(retryAfter = 5).assert().isNotEqualTo(problem)
        problem.copyWith(retryAfter = 5).retryAfter.assert().isEqualTo(5)
        problem.copyWith(version = 2).assert().isNotEqualTo(problem)
        problem.copyWith(version = 2).toString().assert().endsWith("version=2, retry=SAME_REQUEST)")
        problem.toString().assert().startsWith("Problem(")
    }
}
