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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/**
 * A specification of a type the host fixes to a tenant (`aggregateSpec(fixedTenant = …)`, #6556) admits its commands
 * as the gateway does: the aggregate is in the fixed tenant, and a caller of any tenant sends its commands there. Its
 * owner is compared by id alone, so an owner id means one owner whichever tenant its caller is of: the owner ids of a
 * fixed tenant's type are unique across the callers' tenants.
 */
class FixedTenantSpecTest {
    private data class Fill(val amount: Int)
    private data class Filled(val amount: Int)

    private class Wallet {
        var total = 0
    }

    /** A wallet per owner, its id its owner's. */
    private val wallet = aggregate<Wallet>("bank", "wallet") {
        initial { Wallet() }
        id(AggregateIdSource.OWNER)
        decide<Fill>(Creation.ALLOWED) { _, body -> Decision.events(Filled(body.amount)) }
        evolve<Filled> { total += it.amount }
    }

    /** An account owned by the owner who opens it, with an id of its own. */
    private val account = aggregate<Wallet>("bank", "account") {
        initial { Wallet() }
        decide<Fill>(Creation.ALLOWED) { _, body -> Decision.events(Filled(body.amount)) }
        evolve<Filled> { total += it.amount }
    }

    private fun caller(tenant: String, owner: String) = Caller.of(tenant, Scope(ownerId = owner), subject = owner)

    @Test
    fun `a caller of any tenant sends its commands to the aggregate in the fixed tenant`() =
        aggregateSpec(account, caller = caller("acme", "alice"), fixedTenant = SHARED) {
            ref.tenantId.assert().isEqualTo(SHARED)
            whenCommand(Fill(1)) { expectEvents(Filled(1)) }
            // Without the fixed tenant, a caller of another tenant than the specification's is refused as malformed.
            whenCommand(Fill(2), caller = caller("globex", "alice")) { expectEvents(Filled(2)) }
        }

    @Test
    fun `a specification's tenant is its type's fixed one`() {
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(account, tenantId = "acme", fixedTenant = SHARED) { }
        }.hasMessage(
            "The type bank.account is fixed to tenant shared: its aggregate aggregate-1 is in it, not in acme."
        )
    }

    @Test
    fun `under a fixed tenant one owner id is one owner, whichever tenant its caller is of`() =
        aggregateSpec(wallet, id = "u1", fixedTenant = SHARED) {
            // An aggregate whose id is its owner's: u1 of acme and u1 of globex share one wallet.
            whenCommand(Fill(1), caller = caller("acme", "u1")) { expectEvents(Filled(1)) }
            whenCommand(Fill(2), caller = caller("globex", "u1")) {
                expectEvents(Filled(2))
                expectState { total.assert().isEqualTo(3) }
            }
        }

    @Test
    fun `under a fixed tenant an owner's aggregate is reached by the same owner id of another tenant`() =
        aggregateSpec(account, fixedTenant = SHARED) {
            // An aggregate of its own id, owned by u1 of acme: u1 of globex changes it, u2 of acme may not (a command
            // that may create it is forbidden, one that may not is answered as one that does not exist).
            whenCommand(Fill(1), caller = caller("acme", "u1")) { expectEvents(Filled(1)) }
            whenCommand(Fill(2), caller = caller("globex", "u1")) { expectEvents(Filled(2)) }
            whenCommand(Fill(3), caller = caller("acme", "u2")) { expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN) }
        }

    private companion object {
        const val SHARED = "shared"
    }
}
