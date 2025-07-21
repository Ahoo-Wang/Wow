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
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.test.aggregate.ExpectStage
import me.ahoo.wow.test.aggregate.ExpectedResult
import org.junit.jupiter.api.DynamicNode

interface DynamicTestBuilder {
    val dynamicNodes: List<DynamicNode>
}

abstract class AbstractDynamicTestBuilder : DynamicTestBuilder {
    override val dynamicNodes: MutableList<DynamicNode> = mutableListOf()
}

interface AggregateDsl<S : Any> {
    fun given(block: GivenDsl<S>.() -> Unit)
}

interface GivenDsl<S : Any> : WhenDsl<S> {
    fun inject(inject: ServiceProvider.() -> Unit)

    fun givenOwnerId(ownerId: String)

    fun givenEvent(events: Array<out Any> = emptyArray(), block: WhenDsl<S>.() -> Unit)

    fun givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit)
}

interface WhenDsl<S : Any> {
    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.Companion.empty(),
        ownerId: String = OwnerId.Companion.DEFAULT_OWNER_ID,
        block: ExpectDsl<S>.() -> Unit
    )
}

interface ExpectDsl<S : Any> : ExpectStage<S> {
    fun fork(
        verifyError: Boolean = false,
        block: ForkedVerifiedStageDsl<S>.() -> Unit
    )
}

interface ForkedVerifiedStageDsl<S : Any> : GivenDsl<S> {
    val verifiedResult: ExpectedResult<S>
    val stateAggregate: StateAggregate<S>
        get() = verifiedResult.stateAggregate
    val stateRoot: S
        get() = stateAggregate.state
}
