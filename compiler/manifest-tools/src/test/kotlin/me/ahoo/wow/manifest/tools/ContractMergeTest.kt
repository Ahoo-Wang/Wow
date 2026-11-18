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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ContractException
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SubscriptionStart
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A service's merge against the api modules on its class path, their types and their contracts: a contract whose
 * classes its types lack, and an event whose api module carries no contract (a types-only release).
 */
class ContractMergeTest {
    @TempDir
    lateinit var directory: File

    /** The service's own module: a saga of `payment` taking `billing.Charged` by type. */
    private fun service(): File = File(directory, "payment").also { classes ->
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "payment",
                    subscriptions = listOf(
                        SubscriptionManifest(
                            name = "Saga.onCharged",
                            kind = SubscriptionKind.SAGA,
                            source = SubscriptionSource(
                                events = listOf("Charged"),
                                eventSchemas = listOf(Manifest.schemaRef("billing.Charged")),
                            ),
                            function = FunctionManifest(FunctionKind.SAGA, "payment", "Saga", "onCharged"),
                            start = SubscriptionStart.BEGINNING,
                        ),
                    ),
                ),
            ),
        )
        ManifestJson.write(manifest, File(classes, "${Manifest.FRAGMENT_DIRECTORY}/payment.json"))
    }

    /** A class path directory [name] holding the class files of [classes], and [contract] when given. */
    private fun entry(name: String, vararg classes: String, contract: Manifest? = null): File =
        File(directory, name).also { entry ->
            classes.forEach { File(entry, it.replace('.', '/') + ".class").apply { parentFile.mkdirs() }.writeText("") }
            contract?.let { ManifestJson.write(it, File(entry, PeerContracts.RESOURCE)) }
        }

    private fun merge(classpath: List<File>, owners: Map<String, String>) = ManifestTools.merge(
        classpath,
        owners,
        "payment-service",
        "fallback",
        null,
        File(directory, "manifest.json"),
    )

    @Test
    fun `a contract whose classes its types lack should fail the merge, naming both`() {
        val billing = Manifest(
            contexts = listOf(
                ContextManifest(
                    "billing",
                    aggregates = listOf(
                        AggregateManifest(
                            "invoice",
                            state = StateManifest(Manifest.schemaRef("billing.InvoiceState")),
                            commands = listOf(
                                CommandManifest("Issue", schema = Manifest.schemaRef("billing.Issue")),
                                CommandManifest("Void", schema = Manifest.schemaRef("billing.Void")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // The contract of version 1, beside the types of version 2, which no longer hold `Void`.
        val contract = entry("billing-contract", contract = billing)
        val types = entry("billing-types", "billing.Issue")
        val owners = mapOf(contract.path to "sample:billing-api:1.0.0", types.path to "sample:billing-api:2.0.0")

        assertThrows<IllegalStateException> { merge(listOf(service(), contract, types), owners) }.message.assert()
            .contains(
                "The contracts on the class path do not match their types:",
                "billing.Void, which the contract in sample:billing-api:1.0.0 lists, is in no class path entry, " +
                    "while its other classes are in sample:billing-api:2.0.0",
            )
    }

    @Test
    fun `an event whose api module carries no contract should fail the merge as a types-only release`() {
        val types = entry("billing-types", "billing.Charged")
        val owners = mapOf(types.path to "sample:billing-api:1.0.0-types")

        assertThrows<ContractException> { merge(listOf(service(), types), owners) }.problems.single().assert()
            .contains(
                "takes billing.Charged, which no peer contract on the class path lists as an event: it is in " +
                    "sample:billing-api:1.0.0-types, whose api module carries no contract listing it",
                "-Pwow.contract.publish=false",
            )
    }
}
