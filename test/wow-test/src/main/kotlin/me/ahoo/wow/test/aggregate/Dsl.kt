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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.messaging.DefaultHeader

class AggregateDsl<S : Any> {

}


class GivenDsl<S : Any>(private val givenStage: GivenStage<S>) {
    fun givenEvent(events: Array<out Any>, block: WhenDsl<S>.() -> Unit) {
        val whenStage = givenStage.givenEvent(*events)
        val whenDsl = WhenDsl(whenStage)
        block(whenDsl)
    }

    fun whenCommand(block: WhenDsl<S>.() -> Unit) {
        val whenStage = givenStage.givenEvent()
        val whenDsl = WhenDsl(whenStage)
        block(whenDsl)
    }
}


class WhenDsl<S : Any>(private val whenStage: WhenStage<S>) {
    private val expectTests: MutableList<ExpectStage<S>> = mutableListOf()
    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.Companion.empty(),
        ownerId: String = OwnerId.Companion.DEFAULT_OWNER_ID,
        block: ExpectStage<S>.() -> Unit
    ) {
        val expectStage = whenStage.whenCommand(command, header, ownerId)
        block(expectStage)
        expectTests.add(expectStage)
    }

    fun build(): List<ExpectStage<S>> {
        return expectTests
    }
}

class ExpectDsl<S : Any>(private val expectStage: ExpectStage<S>) {
    fun whenCommand(
        block: GivenDsl<S>.() -> Unit
    ) {
        val givenStage = expectStage.verify().then()
        val expectStage = GivenDsl(givenStage)
        block(expectStage)
    }
}