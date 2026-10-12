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

package me.ahoo.wow.runtime.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.cluster.Account
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.Deposited
import me.ahoo.wow.runtime.cluster.accountManifest
import me.ahoo.wow.runtime.node.accountModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import me.ahoo.wow.manifest.Creation as ManifestCreation

/** A node refuses to start when its Manifest and its models disagree (decision D108). */
class ManifestCheckTest {
    private val manifest = accountManifest(context = "bank")

    /** [manifest] with its account aggregate changed by [change]. */
    private fun changed(
        change: (me.ahoo.wow.manifest.AggregateManifest) -> me.ahoo.wow.manifest.AggregateManifest,
    ): Manifest {
        val context = manifest.contexts.single()
        return manifest.copy(contexts = listOf(context.copy(aggregates = context.aggregates.map(change))))
    }

    @Test
    fun `a Manifest that says what the models do agrees with them`() {
        ManifestCheck.differences(manifest, listOf(accountModel("bank"))).assert().isEmpty()
        ManifestCheck.check(manifest, listOf(accountModel("bank")))
    }

    @Test
    fun `commands, former names and creation are compared`() {
        val deposit = manifest.contexts.single().aggregates.single().commands.single()
        val differing = changed { aggregate ->
            aggregate.copy(
                commands = listOf(
                    deposit.copy(aliases = listOf("Pay"), creation = ManifestCreation.REQUIRED),
                    CommandManifest("Withdraw", schema = Manifest.schemaRef("Withdraw")),
                ),
            )
        }

        assertThrows<ManifestMismatchException> { ManifestCheck.check(differing, listOf(accountModel("bank"))) }
            .differences.assert().containsExactly(
                "command bank.account.Deposit has the former name bank.account.Pay in the Manifest but not in the " +
                    "model of bank.account",
                "command bank.account.Deposit creates its aggregate REQUIRED in the Manifest, ALLOWED in the model",
                "command bank.account.Withdraw is in the Manifest but the model of bank.account does not decide it",
            )
    }

    @Test
    fun `a command the model knows under another name is not the Manifest's`() {
        val renamed = aggregate<Account>("bank", "account") {
            initial { Account() }
            decide<Deposit>(Creation.ALLOWED, name = "bank.account.Credit") { _, body ->
                Decision.events(Deposited(body.amount))
            }
            alias<Deposit>("bank.account.Deposit")
            evolve<Deposited> { balance += it.amount }
        }
        ManifestCheck.differences(manifest, listOf(renamed)).assert().containsExactly(
            "command bank.account.Deposit is in the Manifest but the model of bank.account does not decide it",
        )
    }

    @Test
    fun `events and their revisions are compared`() {
        val deposited = manifest.contexts.single().aggregates.single().events.single()
        val differing = changed { aggregate ->
            aggregate.copy(events = listOf(deposited.copy(revision = 2), deposited.copy(name = "Withdrawn")))
        }
        ManifestCheck.differences(differing, listOf(accountModel("bank"))).assert().containsExactly(
            "event bank.account.Deposited is of revision 2 in the Manifest, 1 in the model",
            "event bank.account.Withdrawn is in the Manifest but the model of bank.account does not name it so",
        )
    }

    @Test
    fun `the lifecycle commands are compared both ways`() {
        val narrowed = aggregate<Account>("bank", "account") {
            initial { Account() }
            decide<Deposit>(Creation.ALLOWED) { _, body -> Decision.events(Deposited(body.amount)) }
            evolve<Deposited> { balance += it.amount }
            lifecycle(DeleteAggregate::class.java)
        }
        val differing = changed { it.copy(lifecycle = listOf(Lifecycle.DELETE_AGGREGATE, "wow.lifecycle.Archive")) }
        ManifestCheck.differences(differing, listOf(narrowed)).assert().containsExactly(
            "lifecycle command wow.lifecycle.Archive of bank.account is in the Manifest but not handled by the model",
        )
        ManifestCheck.differences(manifest, listOf(narrowed)).assert().hasSize(Lifecycle.COMMANDS.size - 1)
    }
}
