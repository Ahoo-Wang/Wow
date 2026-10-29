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

package me.ahoo.wow.analytics.catalog.server

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetField
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetReference
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetScope
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetVersion
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetState
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import java.io.IOException

/** The reference actions' rules apart from a node: what a row offers, and what the relay refuses or passes through. */
class DatasetReferenceActionsTest {
    private val caller = Caller.of(tenantId = "t1", subject = "mary")

    private val call = object : RouteCall {
        override val caller: Caller = this@DatasetReferenceActionsTest.caller
        override val tenantId: String = "t1"
        override val scope: Scope = Scope.NONE
        override val requestId: String = "r-1"
        override val commands: CommandClient = mockk()
    }
    private val reference = DatasetReference("crm", "member")

    private fun state(status: DatasetStatus = DatasetStatus.PUBLISHED, current: Int = 1): DatasetState {
        val version = DatasetVersion(
            DatasetArtifact(
                context = "shop",
                name = "invoices",
                version = 1,
                database = "wow_datasets",
                view = "ds_shop_invoices_v1",
                ddl = "",
                scope = DatasetScope(tenant = "tenant"),
                fields = listOf(DatasetField("member", reference = reference), DatasetField("total")),
                sources = emptyList(),
            ),
            DatasetDefinition(emptyList(), "i", emptyList()),
        )
        return mockk {
            every { exists } returns true
            every { this@mockk.status } returns status
            every { published() } returns version.takeIf { current == 1 }
        }
    }

    private fun actions(
        dataset: DatasetState? = state(),
        manifest: suspend () -> Manifest = { MANIFEST },
        described: Set<String> = setOf("crm"),
    ) = DatasetReferenceActions({ dataset }, { _, _ -> manifest() }, described)

    @Test
    fun `a dataset not served, or retired, offers nothing`(): Unit = runBlocking {
        actions(dataset = null).actionsOf("shop.invoices", caller).assert().isNull()
        actions(dataset = state(DatasetStatus.RETIRED)).actionsOf("shop.invoices", caller).assert().isNull()
        actions(dataset = state(DatasetStatus.DRAFT, current = 0)).actionsOf("shop.invoices", caller).assert().isNull()
    }

    @Test
    fun `a context not described, or whose Manifest cannot be read now, offers no commands`(): Unit = runBlocking {
        actions(described = emptySet()).actionsOf("shop.invoices", caller)!!.fields.single().commands.assert().isEmpty()
        actions(manifest = { throw IOException("down") }).actionsOf("shop.invoices", caller)!!
            .fields.single().commands.assert().isEmpty()
        actions(manifest = { manifest() }).actionsOf("shop.invoices", caller)!!
            .fields.single().commands.assert().isEmpty()
    }

    @Test
    fun `only a flat body's scalars make a form`() {
        DatasetReferenceActions.inputsOf(MANIFEST, "#/\$defs/missing").assert().isNull()
        DatasetReferenceActions.inputsOf(MANIFEST, "#/\$defs/crm.Text").assert().isNull()
        DatasetReferenceActions.inputsOf(MANIFEST, "#/\$defs/crm.Bare").assert().isEmpty()
        DatasetReferenceActions.inputsOf(MANIFEST, "#/\$defs/crm.Untyped").assert().isNull()
        DatasetReferenceActions.inputsOf(MANIFEST, "#/\$defs/crm.Odd").assert().isNull()
        DatasetReferenceActions.inputsOf(MANIFEST, "#/\$defs/crm.Two").assert().isNull()
        DatasetReferenceActions.offered(MANIFEST, reference).map { it.first.name }.assert().containsExactly("Bare")
    }

    @Test
    fun `a rejection's status is the peer's, the framework's or a domain rejection's`() {
        val manifest = manifest(
            errors = listOf(error("crm.top", 409)),
            contexts = listOf(context("crm", errors = listOf(error("crm.member.gone", 410)))),
        )
        DatasetReferenceActions.statusOf(manifest, "crm.top").assert().isEqualTo(409)
        DatasetReferenceActions.statusOf(manifest, "crm.member.gone").assert().isEqualTo(410)
        DatasetReferenceActions.statusOf(manifest, FrameworkErrors.AUTH_FORBIDDEN).assert().isEqualTo(403)
        DatasetReferenceActions.statusOf(manifest, "crm.member.suspended")
            .assert().isEqualTo(FrameworkErrors.DOMAIN_REJECTION_STATUS)
    }

    @Test
    fun `a technical failure of the owning service passes through in its code`() {
        val commands = mockk<CommandClient> {
            every { of(any()) } returns this
            coEvery { wait(any()) } throws
                WowProblemException("wow.command.busy", 503, RetryHint.NONE, "Busy.")
        }
        val refusal = assertThrownBy<WowProblemException> {
            runBlocking {
                actions().send(
                    "shop.invoices",
                    "member",
                    "m-1",
                    "Bare",
                    WowJson.mapper.createObjectNode(),
                    caller,
                    commands
                )
            }
        }.actual()
        refusal.status.assert().isEqualTo(503)
        refusal.code.assert().isEqualTo("wow.command.busy")
    }

    @Test
    fun `a context not described relays nothing`() {
        val refusal = assertThrownBy<RouteRefusal> {
            runBlocking {
                actions(described = emptySet())
                    .send("shop.invoices", "member", "m-1", "Bare", WowJson.mapper.createObjectNode(), caller, mockk())
            }
        }.actual()
        refusal.code.assert().isEqualTo(FrameworkErrors.COMMAND_UNHANDLED)
    }

    @Test
    fun `a dataset not served, or a field that refers to nothing, is refused by its code`() {
        val notServed = assertThrownBy<RouteRefusal> {
            runBlocking { actions(dataset = null).referenceActions(call, "shop.invoices") }
        }.actual()
        notServed.code.assert().isEqualTo(ReferenceActionErrors.DATASET_NOT_SERVED)
        val notAReference = assertThrownBy<RouteRefusal> {
            runBlocking {
                actions().sendReferenceAction(
                    call,
                    "shop.invoices",
                    "total",
                    "m-1",
                    "Bare",
                    WowJson.mapper.createObjectNode()
                )
            }
        }.actual()
        notAReference.code.assert().isEqualTo(ReferenceActionErrors.NOT_A_REFERENCE)
    }

    @Test
    fun `a rejection of the owning service passes through in its code, with the status that service answers`() {
        val rejected = mockk<CommandResult>(relaxed = true) {
            every { status } returns CommandResult.Status.REJECTED
            every { error } returns mockk<ErrorInfo> {
                every { code } returns "crm.member.gone"
                every { message } returns "Gone."
            }
        }
        val commands = mockk<CommandClient> {
            every { of(any()) } returns this
            coEvery { wait(any()) } throws CommandRejectedException(rejected)
        }
        val passed = assertThrownBy<WowProblemException> {
            runBlocking {
                actions().send(
                    "shop.invoices",
                    "member",
                    "m-1",
                    "Bare",
                    WowJson.mapper.createObjectNode(),
                    caller,
                    commands
                )
            }
        }.actual()
        passed.status.assert().isEqualTo(FrameworkErrors.DOMAIN_REJECTION_STATUS)
        passed.code.assert().isEqualTo("crm.member.gone")
        passed.message.assert().isEqualTo("Gone.")
        passed.result.assert().isSameAs(rejected)
    }

    private companion object {
        val MANIFEST = manifest(
            contexts = listOf(
                context(
                    name = "crm",
                    aggregates = listOf(
                        aggregate(
                            name = "member",
                            state = "#/\$defs/crm.State",
                            commands = listOf(
                                command(name = "Bare", schema = "#/\$defs/crm.Bare"),
                                command(name = "Text", schema = "#/\$defs/crm.Text"),
                                command(name = "Untyped", schema = "#/\$defs/crm.Untyped"),
                                command(name = "Odd", schema = "#/\$defs/crm.Odd"),
                                command(name = "Two", schema = "#/\$defs/crm.Two"),
                                command(name = "Mounted", schema = "#/\$defs/crm.Bare", mounted = true),
                            ),
                        ),
                    ),
                ),
            ),
            defs = mapOf(
                "crm.Bare" to mapOf("type" to "object"),
                "crm.Text" to mapOf("type" to "string"),
                "crm.Untyped" to mapOf("type" to "object", "properties" to mapOf("a" to mapOf("format" to "x"))),
                "crm.Odd" to mapOf("type" to "object", "properties" to mapOf("a" to "not a schema")),
                "crm.Two" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("a" to mapOf("type" to listOf("string", "integer"))),
                ),
            ),
        )
    }
}
