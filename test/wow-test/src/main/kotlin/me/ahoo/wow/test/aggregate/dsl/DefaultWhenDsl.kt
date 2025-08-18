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

package me.ahoo.wow.test.aggregate.dsl

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.test.aggregate.WhenStage
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import org.junit.jupiter.api.DynamicContainer

class DefaultWhenDsl<S : Any>(private val delegate: WhenStage<S>) : WhenDsl<S>, Named, AbstractDynamicTestBuilder() {
    override var name: String = ""
        private set

    override fun name(name: String) {
        this.name = name
    }

    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val expectStage = delegate.whenCommand(command, header, ownerId)
        val expectDsl = DefaultExpectDsl(expectStage)
        block(expectDsl)
        val displayName = buildString {
            append("When[${command.javaClass.simpleName}]")
            appendName(name)
        }
        val dynamicTest = DynamicContainer.dynamicContainer(displayName, expectDsl.dynamicNodes)
        dynamicNodes.add(dynamicTest)
    }
}
