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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/**
 * One command decides its tenant and scope alike through the gateway and the test DSL (issue #6561), both by
 * `CallerAdmission.admit`: a caller the gateway refuses fails a specification with the gateway's answer, and one it
 * admits is recorded in the same tenant and scope.
 */
class TenantScopePathsTest : CommandGatewayFixtures() {
    private val ref = model.type.ref(TENANT, "cart-1")

    /** The gateway's answer to [caller]'s AddItem to the cart, acting in [target] when given. */
    private fun gateway(caller: Caller, target: TargetScope? = null): CommandResult = runBlocking {
        val request = ServerCommandRequest.builder(AddItem("apple"), ref).caller(caller)
        target?.let { request.target(it) }
        gateway().execute(request.build())
    }

    /** What the specification of the cart says of [caller]'s AddItem, acting in [scope] when given. */
    private fun spec(caller: Caller, scope: Scope? = null) =
        aggregateSpec(model, id = ref.id, tenantId = ref.tenantId) {
            whenCommand(AddItem("apple"), scope = scope, caller = caller) { expectNoError() }
        }

    /** The gateway refuses [caller] in [target] with [code], and the specification fails with its answer. */
    private fun refusedAlike(code: String, caller: Caller, target: TargetScope? = null) {
        val refused = gateway(caller, target)
        refused.assertRefused(code)
        assertThrownBy<IllegalArgumentException> { spec(caller, target?.scope) }.hasMessage(
            "The gateway refuses AddItem from this caller, $code: ${refused.error!!.message} A specification sends " +
                "only what admission takes: give a caller of the specification's tenant, or one that acts across " +
                "scopes (crossScope) for another tenant or scope.",
        )
        commands.assert().isEmpty()
    }

    @Test
    fun `a caller of another tenant is refused as malformed by the gateway and fails a spec with that answer`() {
        refusedAlike(FrameworkCodes.COMMAND_MALFORMED, Caller("globex", Scope("carol"), subject = "carol"))
    }

    @Test
    fun `a caller naming another scope without crossScope is forbidden by the gateway and fails a spec so`() {
        refusedAlike(FrameworkCodes.AUTH_FORBIDDEN, alice, TargetScope.of(TENANT, Scope("bob", "home")))
    }

    @Test
    fun `a crossScope caller of another tenant acts in the target's tenant and scope on either path`() {
        val target = TargetScope.of(TENANT, Scope("bob", "home"))
        gateway(admin, target).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        spec(admin, target.scope)
        commands.map { listOf(it.ref, it.scope, it.operator) }.distinct().assert().hasSize(1)
        commands.first().ref.assert().isEqualTo(ref)
        commands.first().scope.assert().isEqualTo(Scope("bob", "home"))
    }
}
