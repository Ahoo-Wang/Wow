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
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.messaging.TestMessageBody
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
    fun `concurrent unregister cannot leave a function in topic index`() {
        val indexEntered = CountDownLatch(1)
        val releaseIndex = CountDownLatch(1)
        val function = BlockingTopicsRegistrarFunction(topic, indexEntered, releaseIndex)
        val registrar = SimpleMessageFunctionRegistrar<BlockingTopicsRegistrarFunction>()
        val registerFailure = AtomicReference<Throwable?>()
        val unregisterFailure = AtomicReference<Throwable?>()
        val registerThread = thread(start = false, name = "registrar-register") {
            runCatching { registrar.register(function) }
                .onFailure(registerFailure::set)
        }
        val unregisterThread = thread(start = false, name = "registrar-unregister") {
            runCatching { registrar.unregister(function) }
                .onFailure(unregisterFailure::set)
        }

        try {
            registerThread.start()
            indexEntered.await(5, TimeUnit.SECONDS).assert().isTrue()
            unregisterThread.start()
            waitUntilBlockedOrTerminated(unregisterThread)
        } finally {
            releaseIndex.countDown()
        }
        registerThread.join(5_000)
        unregisterThread.join(5_000)

        registerThread.isAlive.assert().isFalse()
        unregisterThread.isAlive.assert().isFalse()
        registerFailure.get().assert().isNull()
        unregisterFailure.get().assert().isNull()
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

    @Test
    fun `a function without topics that matches messages itself is asked for every message`() {
        val anyTopic = RegistrarFunction(id = "any-topic", supportedTopics = emptySet(), matchesAnyTopic = true)
        val registrar = SimpleMessageFunctionRegistrar<RegistrarFunction>()
        registrar.register(anyTopic)

        registrar.supportedFunctions(TestNamedMessage(body = TestMessageBody())).toList()
            .assert().containsExactly(anyTopic)
    }

    @Test
    fun `topic functions and functions without topics are both returned for a message`() {
        val topicFunction = RegistrarFunction(id = "topic", supportedTopics = setOf(topic))
        val anyTopic = RegistrarFunction(id = "any-topic", supportedTopics = emptySet(), matchesAnyTopic = true)
        val anyTopicOtherType = RegistrarFunction(
            id = "any-topic-other-type",
            supportedType = String::class.java,
            supportedTopics = emptySet(),
            matchesAnyTopic = true,
        )
        val registrar = SimpleMessageFunctionRegistrar<RegistrarFunction>()
        registrar.register(topicFunction)
        registrar.register(anyTopic)
        registrar.register(anyTopicOtherType)

        registrar.supportedFunctions(TestNamedMessage(body = TestMessageBody())).toList()
            .assert().containsExactlyInAnyOrder(topicFunction, anyTopic)
    }

    @Test
    fun `unregistering a function without topics removes it`() {
        val anyTopic = RegistrarFunction(id = "any-topic", supportedTopics = emptySet(), matchesAnyTopic = true)
        val registrar = SimpleMessageFunctionRegistrar<RegistrarFunction>()
        registrar.register(anyTopic)

        registrar.unregister(anyTopic)

        registrar.functions.assert().isEmpty()
        registrar.supportedFunctions(TestNamedMessage(body = TestMessageBody())).toList().assert().isEmpty()
    }

    @Test
    fun `unregistering one of two functions of a topic keeps the other`() {
        val first = RegistrarFunction(id = "first", supportedTopics = setOf(topic))
        val second = RegistrarFunction(id = "second", supportedTopics = setOf(topic))
        val registrar = SimpleMessageFunctionRegistrar<RegistrarFunction>()
        registrar.register(first)
        registrar.register(second)

        registrar.unregister(first)

        registrar.supportedFunctions(TestNamedMessage(body = TestMessageBody())).toList()
            .assert().containsExactly(second)
    }

    private fun waitUntilBlockedOrTerminated(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive && thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        (thread.state == Thread.State.BLOCKED || !thread.isAlive).assert().isTrue()
    }
}

private class BlockingTopicsRegistrarFunction(
    private val topic: NamedAggregate,
    private val indexEntered: CountDownLatch,
    private val releaseIndex: CountDownLatch,
) : MessageFunction<Any, MessageExchange<*, *>, String> {
    private val blockRegisterOnce = AtomicBoolean()

    override val processor: Any = Any()
    override val contextName: String = "wow-core-test"
    override val processorName: String = "BlockingRegistrarProcessor"
    override val name: String = "blocking"
    override val functionKind: FunctionKind = FunctionKind.EVENT
    override val supportedType: Class<*> = TestMessageBody::class.java
    override val supportedTopics: Set<NamedAggregate>
        get() {
            if (Thread.currentThread().name == "registrar-register" && blockRegisterOnce.compareAndSet(false, true)) {
                indexEntered.countDown()
                check(releaseIndex.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release topic indexing." }
            }
            return setOf(topic)
        }

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: MessageExchange<*, *>): String = name
}

private data class RegistrarFunction(
    val id: String,
    override val supportedType: Class<*> = TestMessageBody::class.java,
    override val supportedTopics: Set<NamedAggregate>,
    /** Matches by type only, as a custom function that declares no topics does. */
    val matchesAnyTopic: Boolean = false,
) : MessageFunction<Any, MessageExchange<*, *>, String> {
    override fun <M> supportMessage(message: M): Boolean
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate =
        if (matchesAnyTopic) supportedType.isInstance(message.body) else super.supportMessage(message)

    override val processor: Any = Any()
    override val contextName: String = "wow-core-test"
    override val processorName: String = "RegistrarProcessor"
    override val name: String = id
    override val functionKind: FunctionKind = FunctionKind.EVENT

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: MessageExchange<*, *>): String = id
}
