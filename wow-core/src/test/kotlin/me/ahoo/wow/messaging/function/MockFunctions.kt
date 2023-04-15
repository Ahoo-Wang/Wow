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

import me.ahoo.wow.api.annotation.OnEvent

internal class Body
internal class MockFunction {
    fun onEvent(body: Body) {}
}

internal class MockAnotherFunction {
    fun onEvent(body: Body) {}
}

internal class MockWithMultiAggregateNameFunction {
    @OnEvent("aggregate1", "aggregate2")
    fun onEvent(body: Body) {
    }
}
