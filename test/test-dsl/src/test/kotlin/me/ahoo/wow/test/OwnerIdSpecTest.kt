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

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** A history given to an aggregate whose id is its owner's is in that owner's scope, as the kernel records it. */
class OwnerIdSpecTest {
    private data class Fill(val amount: Int)
    private data class Filled(val amount: Int)

    private class Wallet {
        var total = 0
    }

    private val model = aggregate<Wallet>("bank", "wallet") {
        initial { Wallet() }
        id(AggregateIdSource.OWNER)
        decide<Fill>(Creation.ALLOWED) { _, body -> Decision.events(Filled(body.amount)) }
        evolve<Filled> { total += it.amount }
    }

    @Test
    fun `a history given without a scope is its owner's, so the owner's command goes on`() =
        aggregateSpec(model, id = "alice") {
            given(Filled(1))
            whenCommand(Fill(2), scope = Scope(ownerId = "alice")) {
                expectEvents(Filled(2))
                expectScope(Scope(ownerId = "alice"))
            }
            whenCommand(Fill(3), scope = Scope(ownerId = "bob")) { expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN) }
        }

    @Test
    fun `a state given without a scope is its owner's`() = aggregateSpec(model, id = "alice") {
        givenState { total = 5 }
        whenCommand(Fill(1), scope = Scope(ownerId = "alice")) { expectEvents(Filled(1)) }
    }
}
