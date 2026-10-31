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

package me.ahoo.wow.client

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.decide.Misuse
import org.junit.jupiter.api.Test

/** The JSON body of a command whose body names its aggregate, sent to its route without an id. */
class CommandBodiesTest {
    private fun json(body: ByteArray) = ClientJson.mapper.readTree(body).toString()

    private fun body(body: Any, idProperty: String?, id: String?) =
        json(CommandBodies.of("mail.box.Restock", body, idProperty, id))

    @Test
    fun `a body is written as it is unless its aggregate is named beside it, and then names it`() {
        val named = mapOf("boxId" to "b")
        body(named, null, "x").assert().isEqualTo("""{"boxId":"b"}""")
        body(named, "boxId", null).assert().isEqualTo("""{"boxId":"b"}""")
        body(named, "boxId", "b").assert().isEqualTo("""{"boxId":"b"}""")
        body(mapOf("n" to 1), "boxId", "b").assert().isEqualTo("""{"n":1,"boxId":"b"}""")
    }

    @Test
    fun `a body that names another aggregate, holds no string id or is no object is the caller's mistake`() {
        assertThrownBy<Misuse.Argument> { CommandBodies.of("mail.box.Restock", mapOf("boxId" to "c"), "boxId", "b") }
            .hasMessageContaining("The request names the aggregate b, but the body of mail.box.Restock names c")
        assertThrownBy<Misuse.Argument> { CommandBodies.of("mail.box.Restock", mapOf("boxId" to 1), "boxId", "b") }
            .hasMessageContaining("The body of mail.box.Restock names no aggregate: The body's boxId")
        assertThrownBy<Misuse.Argument> { CommandBodies.of("mail.box.Restock", listOf("b"), "boxId", "b") }
            .hasMessageContaining("The body of mail.box.Restock is no JSON object.")
    }
}
