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

package me.ahoo.wow.cosec

import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.WowHttpOptions
import org.junit.jupiter.api.Test

class CoSecFilterCodesTest {
    @Test
    fun `CoSec's 429 and 503 join the edge's filter codes, once`() {
        val added = CoSecFilterCodes.addTo(WowHttpOptions())
        added.filterCodes.assert()
            .containsExactly(FrameworkCodes.REQUEST_RATE_LIMITED, FrameworkCodes.AUTH_UNAVAILABLE)
        CoSecFilterCodes.addTo(added).filterCodes.assert().isEqualTo(added.filterCodes)
    }

    @Test
    fun `they should be the codes CoSec's refusals are written with, beside the public port's own`() {
        CoSecProblemRenderer.codeOf(ProblemType.TOO_MANY_REQUESTS).assert()
            .isEqualTo(FrameworkCodes.REQUEST_RATE_LIMITED)
        CoSecProblemRenderer.codeOf(ProblemType.UNAVAILABLE).assert().isEqualTo(FrameworkCodes.AUTH_UNAVAILABLE)
    }
}
