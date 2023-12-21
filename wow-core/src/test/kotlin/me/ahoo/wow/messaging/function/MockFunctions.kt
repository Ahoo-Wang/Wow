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

@file:Suppress("unused", "UNUSED_PARAMETER")

package me.ahoo.wow.messaging.function

import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.event.DomainEvent

internal class MockEventBody
internal class MockFunction {
    @Retry
    fun onEvent(mockEventBody: MockEventBody) = Unit
}

internal class MockWithWrappedFunction {
    fun onEvent(event: DomainEvent<MockEventBody>) = Unit
}

internal class MockAnotherFunction {
    fun onEvent(mockEventBody: MockEventBody) = Unit
}

internal class MockWithMultiAggregateNameFunction {
    @OnEvent("aggregate1", "aggregate2")
    fun onEvent(mockEventBody: MockEventBody) = Unit
}

internal class MockOnStateEventFunction {
    @OnStateEvent("aggregate1")
    fun onStateEvent(event: DomainEvent<MockEventBody>) = Unit
}

interface ExternalService

internal class MockWithInjectableFunction {
    fun onEvent(mockEventBody: MockEventBody, @Name("externalService") externalService: ExternalService) = Unit
}
