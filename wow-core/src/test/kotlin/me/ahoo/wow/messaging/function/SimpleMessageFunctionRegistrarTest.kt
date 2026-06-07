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

package me.ahoo.wow.messaging.function

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.TestMessageBody
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

class SimpleMessageFunctionRegistrarTest {

    private val topic = MaterializedNamedAggregate("wow-core-test", "messaging_aggregate")

    @Test
    fun `register indexes functions and supportedFunctions returns matching message handlers`() {
        val matching = RegistrarFunction(id = "matching", supportedTopics = setOf(topic))
        val otherTopic = RegistrarFunction(
            id = "other-topic",
            supportedTopics = setOf(MaterializedNamedAggregate("wow-core-test", "other")),
        )
        val otherType =
            RegistrarFunction(
                id = "other-type",
                supportedType = String::class.java,
                supportedTopics = setOf(topic),
            )
        val registrar = SimpleMessageFunctionRegistrar<RegistrarFunction>()

        registrar.register(matching)
        registrar.register(matching)
        registrar.register(otherTopic)
        registrar.register(otherType)

        registrar.functions.assert().hasSize(3)
        registrar.supportedFunctions(TestNamedMessage(body = TestMessageBody())).toList()
            .assert().isEqualTo(listOf(matching))
    }

    @Test
    fun `unregister removes function from registrar and topic index`() {
        val function = RegistrarFunction(id = "matching", supportedTopics = setOf(topic))
        val registrar = SimpleMessageFunctionRegistrar<RegistrarFunction>()
        registrar.register(function)

        registrar.unregister(function)

        registrar.functions.assert().isEmpty()
        registrar.supportedFunctions(TestNamedMessage(body = TestMessageBody())).toList()
            .assert().isEmpty()
    }

    @Test
    fun `filter creates a scoped registrar without mutating the source registrar`() {
        val matching = RegistrarFunction(id = "matching", supportedTopics = setOf(topic))
        val skipped = RegistrarFunction(id = "skipped", supportedTopics = setOf(topic))
        val registrar = SimpleMessageFunctionRegistrar<RegistrarFunction>()
        registrar.register(matching)
        registrar.register(skipped)

        val filtered = registrar.filter { it.id == "matching" }

        registrar.functions.assert().hasSize(2)
        filtered.functions.assert().containsExactly(matching)
        filtered.supportedFunctions(TestNamedMessage(body = TestMessageBody())).toList()
            .assert().isEqualTo(listOf(matching))
    }
}

private data class RegistrarFunction(
    val id: String,
    override val supportedType: Class<*> = TestMessageBody::class.java,
    override val supportedTopics: Set<NamedAggregate>
) : MessageFunction<Any, MessageExchange<*, *>, String> {
    override val processor: Any = Any()
    override val contextName: String = "wow-core-test"
    override val processorName: String = "RegistrarProcessor"
    override val name: String = id
    override val functionKind: FunctionKind = FunctionKind.EVENT

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: MessageExchange<*, *>): String = id
}
