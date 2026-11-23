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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test

/**
 * The authorizer that decides Wow's operational commands ([CommandAuthorizer.operational]) and what decides them in a
 * node's set of authorizers ([CommandAuthorizer.decidesOperational]).
 */
class OperationalAuthorizerTest {
    data class PlaceOrder(val sku: String)

    private val rebuild = CommandAction.of(
        CommandType.of(
            SnapshotRebuilds.REBUILD_SNAPSHOT,
            SnapshotRebuilds.COMMANDS.getValue(SnapshotRebuilds.REBUILD_SNAPSHOT)
        ),
        AggregateType("shop", "cart").ref("t", "cart"),
        Scope.NONE,
        Creation.NONE,
    )
    private val place = CommandAction.of(
        CommandType.of("PlaceOrder", PlaceOrder::class.java),
        AggregateType("shop", "order").ref("t", "o-1"),
        Scope.NONE,
        Creation.NONE,
    )
    private val asked = mutableListOf<CommandAction>()
    private val operators = CommandAuthorizer.operational { caller, action ->
        asked += action
        if (caller.hasRole("operator")) Authorization.Allowed else Authorization.Denied.of("Not an operator.")
    }

    @Test
    fun `it decides the operational commands and has no objection to any other`(): Unit = runBlocking {
        operators.decidesOperational.assert().isTrue()
        operators.toString().assert().startsWith("CommandAuthorizer.operational(")
        operators.authorize(Caller.of(subject = "o", roles = listOf("operator")), rebuild)
            .assert().isEqualTo(Authorization.Allowed)
        operators.authorize(Caller.owner("alice"), rebuild).assert().isInstanceOf(Authorization.Denied::class.java)
        operators.authorize(Caller.owner("alice"), place).assert().isEqualTo(Authorization.Allowed)
        asked.assert().containsExactly(rebuild, rebuild)
    }

    @Test
    fun `a service identity is not asked about, a call between services being trusted`(): Unit = runBlocking {
        operators.authorize(Caller.ofService("ops-service"), rebuild).assert().isEqualTo(Authorization.Allowed)
        asked.assert().isEmpty()
    }

    @Test
    fun `only an authorizer that says so decides them, alone or among others`() {
        CommandAuthorizer { _, _ -> Authorization.Allowed }.decidesOperational.assert().isFalse()
        CommandAuthorizer.ALLOW_ALL.decidesOperational.assert().isFalse()
        CommandAuthorizer.allOf(emptyList()).decidesOperational.assert().isFalse()
        CommandAuthorizer.allOf(listOf(CommandAuthorizer.ALLOW_ALL, operators)).decidesOperational.assert().isTrue()
        CommandAuthorizer.allOf(listOf(CommandAuthorizer.ALLOW_ALL, CommandAuthorizer.ALLOW_ALL))
            .decidesOperational.assert().isFalse()
    }
}
