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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Operator
import org.junit.jupiter.api.Test

class NineXOperatorsTest {
    @Test
    fun `command_operator maps to the operator's id, in the stream's tenant`() {
        val headers = mapOf("command_operator" to "user-1", "app" to "web")
        NineXOperators.operatorOf(headers, "tenant-1").assert().isEqualTo(Operator("user-1", "tenant-1"))
        NineXOperators.headersOf(headers).assert().isEqualTo(mapOf("app" to "web"))
    }

    @Test
    fun `a stream without an operator, or with a blank one, has none`() {
        NineXOperators.operatorOf(mapOf("app" to "web"), "tenant-1").assert().isNull()
        NineXOperators.operatorOf(mapOf("command_operator" to " "), "tenant-1").assert().isNull()
        NineXOperators.headersOf(mapOf("app" to "web")).assert().isEqualTo(mapOf("app" to "web"))
    }
}
