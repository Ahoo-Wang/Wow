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

package me.ahoo.wow.viewstore.domain.view

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.ViewStoreContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper

/**
 * The 9.x history of a view, as migrate-data imports it (decision D267): its `ViewCreated` and `ViewSaved` at revision
 * 1, without a kind, read with their config's `kind`; its `ViewAudienceChanged`, history only, evolved as a share or
 * a claim; a 9.x config without a kind fails the read, naming the view.
 */
@OptIn(InternalWowApi::class)
class ViewHistoryTest {
    private val provider = ModelProvider.load().single()
    private val model = provider.models(Dependencies.of(SharedBoardReferences { _, _, _ -> emptyList() }))
        .single { it.type.name == ViewStoreContract.View.NAME }
    private val log = InMemoryEventLog(4)
    private val kernel = Kernel(log)
    private val ref = model.type.ref("t-1", "view-1")

    private fun stream(version: Int, event: String, revision: Int, payload: String) = EventStreamRecord(
        ref,
        version,
        "c-$version",
        "c-$version",
        "view-store.view.CreateView",
        version.toLong(),
        listOf(EventRecord("view-store.view.$event", revision, payload)),
        Scope(ownerId = "alice"),
    )

    private fun append(vararg streams: EventStreamRecord) = runBlocking { streams.forEach { log.append(it) } }

    @Test
    fun `a 9x view's history should read with its config's kind and its audience moves`(): Unit = runBlocking {
        append(
            stream(
                1,
                "ViewCreated",
                1,
                """{"definitionId":"orders","title":"Mine","audience":"personal","appId":"app","config":{"kind":"record"}}""",
            ),
            stream(2, "ViewSaved", 1, """{"config":{"kind":"dashboard","panels":[]}}"""),
            stream(3, "ViewAudienceChanged", 1, """{"audience":"shared","toOwnerId":"(shared)"}"""),
        )
        val shared = kernel.stateAt(model, ref, 3)!!.state as ViewState
        shared.kind.assert().isEqualTo(ViewKind.DASHBOARD)
        shared.audience.assert().isEqualTo(ViewAudience.SHARED)
        shared.config.assert().isEqualTo(mapOf("kind" to "dashboard", "panels" to emptyList<Any>()))
        (kernel.stateAt(model, ref, 1)!!.state as ViewState).kind.assert().isEqualTo(ViewKind.RECORD)

        append(stream(4, "ViewAudienceChanged", 1, """{"audience":"personal","toOwnerId":"bob"}"""))
        (kernel.stateAt(model, ref, 4)!!.state as ViewState).audience.assert().isEqualTo(ViewAudience.PERSONAL)
    }

    @Test
    fun `a 9x config without a known kind should fail the read, naming the view`(): Unit = runBlocking {
        append(
            stream(
                1,
                "ViewCreated",
                1,
                """{"definitionId":"d","title":"t","audience":"personal","appId":"a","config":{}}"""
            )
        )
        assertThrows<IllegalStateException> { kernel.stateAt(model, ref, 1) }.message.assert()
            .contains("view-store.view/t-1/view-1 v1").contains("ViewCreated@1").contains("has no kind")
        assertThrows<IllegalArgumentException> {
            ViewUpgrades.viewSavedFrom1(
                JsonMapper.builder().build().createObjectNode().apply { putObject("config").put("kind", "chart") },
            )
        }.message.assert().contains("\"chart\"")
    }

    @Test
    fun `no command of a view should produce the history-only ViewAudienceChanged`() {
        val tree = checkNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment))
            .use { JsonMapper.builder().build().readValue(it, Map::class.java) }
        val view = ManifestTree.read(tree).contexts.single().aggregates.single { it.name == ViewStoreContract.View.NAME }
        view.events.map { it.name }.assert().contains("ViewAudienceChanged")
        view.events.single { it.name == "ViewCreated" }.revision.assert().isEqualTo(2)
        view.events.single { it.name == "ViewSaved" }.revision.assert().isEqualTo(2)
        view.commands.forEach { command ->
            command.produces.assert().describedAs(command.name).isNotNull()
            command.produces!!.assert().describedAs(command.name).doesNotContain("ViewAudienceChanged")
        }
    }
}
