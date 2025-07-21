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

package me.ahoo.wow.test.saga.stateless.dsl

import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.naming.annotation.toName
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.saga.stateless.ExpectStage
import me.ahoo.wow.test.saga.stateless.ExpectedResult
import me.ahoo.wow.test.saga.stateless.WhenStage
import org.junit.jupiter.api.DynamicTest

class DefaultStatelessSagaDsl<T : Any>(private val processorType: Class<T>) : StatelessSagaDsl<T>,
    AbstractDynamicTestBuilder() {
    override fun on(block: WhenDsl<T>.() -> Unit) {
        val whenStage = processorType.sagaVerifier()
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)
        dynamicNodes.addAll(whenDsl.dynamicNodes)
    }
}

class DefaultWhenDsl<T : Any>(override val delegate: WhenStage<T>) : Decorator<WhenStage<T>>, WhenDsl<T>,
    AbstractDynamicTestBuilder() {
    override fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    override fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean) {
        delegate.functionFilter(filter)
    }

    override fun whenEvent(
        event: Any,
        state: Any?,
        ownerId: String,
        block: ExpectDsl<T>.() -> Unit
    ) {
        val expectStage = delegate.whenEvent(event, state, ownerId)
        val expectDsl = DefaultExpectDsl(expectStage)
        block(expectDsl)
        val displayName = buildString {
            append("When ")
            if (state != null) {
                append("State")
            }
            append(" Event")
            append("[${event.javaClass.toName()}]")
        }
        val dynamicTest = DynamicTest.dynamicTest(displayName) {
            expectStage.verify()
        }
        dynamicNodes.add(dynamicTest)
    }
}

class DefaultExpectDsl<T : Any>(override val delegate: ExpectStage<T>) : Decorator<ExpectStage<T>>, ExpectDsl<T> {
    override fun expect(expected: ExpectedResult<T>.() -> Unit): ExpectDsl<T> {
        delegate.expect(expected)
        return this
    }
}
