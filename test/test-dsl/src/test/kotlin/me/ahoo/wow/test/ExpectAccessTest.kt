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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** `expectAccess`: the access attributes the state derives after a command. */
class ExpectAccessTest {
    data class Create(val department: String)
    data class Share(val with: String)
    data class Created(val department: String)
    data class Shared(val with: String)

    class Document(val id: String) {
        var department: String? = null
        val sharedWith: MutableSet<String> = linkedSetOf()
    }

    private val model = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        decide<Create>(Creation.REQUIRED) { _, body -> Decision.events(Created(body.department)) }
        decide<Share> { state, body ->
            if (body.with in state.sharedWith) Decision.NoOp else Decision.events(Shared(body.with))
        }
        evolve<Created> { department = it.department }
        evolve<Shared> { sharedWith += it.with }
        access(AccessAttribute.single("department", String::class.java)) { it.department }
        access(AccessAttribute.multiple("sharedWith", String::class.java)) { it.sharedWith }
    }

    @Test
    fun `the access attributes after the committed events are checked, several values in any order`() =
        aggregateSpec(model) {
            whenCommand(Create("sales")) { expectAccess(access("sales")) }
            whenCommand(Share("bob")) { expectAccess(access("sales", listOf("bob"))) }
            whenCommand(Share("carol")) {
                expectAccess(access("sales", listOf("carol", "bob")))
                assertThrownBy<AssertionError> { expectAccess(access("legal", listOf("bob"))) }
                    .hasMessageContaining("The access attributes differ")
            }
        }

    private fun access(department: String, sharedWith: Collection<String> = emptySet()): Map<String, Any?> =
        mapOf("department" to department, "sharedWith" to sharedWith)

    @Test
    fun `after a command that committed nothing, the attributes are those the aggregate has`() = aggregateSpec(model) {
        given(Created("sales"), Shared("bob"))
        whenCommand(Share("bob")) {
            expectUnchanged()
            expectAccess(mapOf("department" to "sales", "sharedWith" to setOf("bob")))
        }
    }
}
