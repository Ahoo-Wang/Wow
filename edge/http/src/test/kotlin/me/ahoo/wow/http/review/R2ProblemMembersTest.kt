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

package me.ahoo.wow.http.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.problem.Problem
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Review r2 (REST): `reference/http.md` (the problem's members) says of a rejection's structured extensions "one named
 * like a member above, or `instance`, is left out". `Problem.members()` (`Problem.kt:80-97`) leaves out only
 * `instance`, `version` and `retry` (`RESERVED`, `:173`) and the members the problem has: an extension named `detail`,
 * `code`, `violations` or `result` on a problem without that member is written as the member, so a client reads a
 * domain's value as Wow's (the TypeScript client takes `violations` as its list of violations).
 */
class R2ProblemMembersTest {
    @ParameterizedTest
    @ValueSource(strings = ["detail", "code", "violations", "result"])
    fun `an extension named like a member should be left out, even on a problem without that member`(name: String) {
        val problem = Problem(422, "Rejected", null, null, extensions = mapOf(name to "the domain's value"))
        problem.members().keys.assert().doesNotContain(name)
    }
}
