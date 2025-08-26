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
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.test.aggregate.GivenStage
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import org.junit.jupiter.api.DynamicContainer

abstract class AbstractGivenStageDsl<S : Any> : Decorator<GivenStage<S>>, GivenDsl<S>, Named,
    AbstractDynamicTestBuilder() {
    final override var name: String = ""
        private set

    override fun name(name: String) {
        this.name = name
    }

    override fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    override fun givenOwnerId(ownerId: String) {
        delegate.givenOwnerId(ownerId)
    }

    override fun givenEvent(event: Any, block: WhenDsl<S>.() -> Unit) {
        givenEvent(arrayOf(event), block)
    }

    override fun givenEvent(events: Array<out Any>, block: WhenDsl<S>.() -> Unit) {
        val whenStage = delegate.givenEvent(*events)
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)

        val displayName = buildString {
            append("Given")
            append(" Events[${events.joinToString(",") { it.javaClass.simpleName }}]")
            appendName(name)
        }
        val container = DynamicContainer.dynamicContainer(
            displayName,
            whenDsl.dynamicNodes
        )
        dynamicNodes.add(container)
    }

    override fun givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit) {
        val whenStage = delegate.givenState(state, version)
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)
        val displayName = buildString {
            append("Given[State]")
            appendName(name)
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }

    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val givenStage = delegate.givenEvent()
        val whenDsl = DefaultWhenDsl(givenStage)
        whenDsl.whenCommand(command, header, ownerId, block)
        val displayName = buildString {
            append("Given[Empty]")
            appendName(name)
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }
}
