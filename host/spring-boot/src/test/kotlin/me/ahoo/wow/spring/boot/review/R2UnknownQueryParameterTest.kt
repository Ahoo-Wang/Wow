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

package me.ahoo.wow.spring.boot.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test

/**
 * Review r2 (REST): `reference/http.md` ("What the public port refuses") opens with "Nothing a client sends outside
 * the body is read as something else, or dropped", and the state reads refuse a query parameter they do not take
 * (`StateHandler.kt:360-384`). A command route reads no query parameter and refuses none (`CommandHandler.kt:127-166`):
 * `?x=1`, or a mistyped parameter a client meant to mean something, is dropped and the command runs. After GA,
 * refusing them would break the clients that send them.
 */
class R2UnknownQueryParameterTest {
    @Test
    fun `a command with a query parameter no route takes should be refused, as a state read is`() {
        startHost().use { host ->
            val answer = host.post("/bank/account/open-account?x=1", """{"owner":"gina"}""")
            answer.status.assert().describedAs("${answer.body}").isEqualTo(400)
            answer.body!!["code"].asString().assert().isEqualTo("wow.request.malformed")
        }
    }
}
