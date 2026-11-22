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

package me.ahoo.wow.spring.boot.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetSources
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spring.boot.HostTestApp
import me.ahoo.wow.spring.boot.node.NodeIdentity
import me.ahoo.wow.spring.boot.node.WowModels
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

/** A list of every row. */
private const val LIST = """{"kind":"list","filter":{"op":"match-all"}}"""

/** The flat table of `bank.account`, as the ClickHouse Sink names it. */
private const val ACCOUNT_FLAT = "bank_account_state_flat"

/** A dataset file over [table], whose `owner` [owner] declares (nothing by default), of [lineage] when given. */
private fun accountsFile(table: String = ACCOUNT_FLAT, owner: String = "", lineage: String? = null): String =
    """
    {
      "title": "Accounts",
      "identity": "id",
      "scope": { "tenant": "tenant_id" },
      "storage": { "table": "$table" },
      ${lineage?.let { """"lineage": "$it",""" }.orEmpty()}
      "fields": {
        "id": { "types": ["string"], "nullable": false },
        "tenant_id": { "types": ["string"], "nullable": false },
        "owner": { "types": ["string"], "nullable": false$owner },
        "balance": { "types": ["integer"], "nullable": false }
      }
    }
    """.trimIndent()

/**
 * The account's owner `CONFIDENTIAL` in its state, and the application's dataset backend: the in-memory storage, its
 * table `bank_account_state_flat` holding the row the ClickHouse Sink writes, and what its tables hold read as the
 * ClickHouse backend reads the Sink's tables of the node's Manifest.
 */
@Configuration(proxyBeanMethods = false)
class SensitiveAccounts {
    @Bean
    fun sensitiveOwnerManifest(loader: ManifestLoader, models: WowModels, identity: NodeIdentity): Manifest =
        loader.load(ServiceInfo(identity.service), models.providers).let { manifest ->
            @Suppress("UNCHECKED_CAST")
            val account = manifest.defs.getValue("Account") as Map<String, Any?>
            val properties = account.getValue("properties") as Map<String, Any?>
            val owner = mapOf("type" to "string", "x-wow-sensitive" to mapOf("level" to "confidential"))
            manifest.copyWith(
                defs = manifest.defs + ("Account" to account + ("properties" to properties + ("owner" to owner))),
            )
        }

    @Bean
    fun accountRows(manifest: Manifest): InMemoryDatasetStorage =
        InMemoryDatasetStorage(sources = ClickHouseDatasetSources(manifest, "default")).also { storage ->
            val row = WowJson.mapper.createObjectNode().put("id", "bank.account/(0)/a-1@1").put("tenant_id", "(0)")
                .put("owner", "Alice Smith").put("balance", 0)
            storage.put(ACCOUNT_FLAT, listOf(row))
            storage.put("ledger", listOf(row))
        }
}

/**
 * A service's own dataset over its state's flat table inherits the state's `@Sensitive` (#6587): the probe of the
 * issue, which read the owner raw from the dataset while the snapshot route masked it.
 */
class HostDatasetLineageTest {
    private fun start(config: Path, vararg files: Pair<String, String>) = files.forEach { (name, document) ->
        Files.createDirectories(config.resolve("wow/dataset"))
        Files.writeString(config.resolve("wow/dataset/$name.json"), document)
    }.let {
        startHost(
            "wow.query.dataset.config-directory" to config.toString(),
            sources = listOf(HostTestApp::class.java, SensitiveAccounts::class.java),
        )
    }

    @Test
    fun `a dataset over the state's flat table should mask the owner, as the snapshot route does`(
        @TempDir config: Path,
    ) {
        start(config, "bank.accounts" to accountsFile()).use { host ->
            host.post("/bank/account/a-1/open-account", """{"owner":"Alice Smith"}""").status.assert().isEqualTo(200)
            val snapshot = host.post("/bank/account/snapshot/query", LIST)
            snapshot.body!![0]["state"]["owner"].asString().assert().isEqualTo("***********")
            val listed = host.post("/wow/dataset/bank/accounts/query", LIST)
            listed.status.assert().isEqualTo(200)
            listed.body!![0]["owner"].asString().assert().isEqualTo("***********")
            // Nor compared by its value.
            host.post(
                "/wow/dataset/bank/accounts/query",
                """{"kind":"count","filter":{"op":"eq","field":"owner","value":"Alice Smith"}}""",
            ).status.assert().isEqualTo(400)
        }
    }

    @Test
    fun `a dataset declaring the owner looser than the state should refuse the start, naming the state's path`(
        @TempDir config: Path,
    ) {
        val looser = accountsFile(owner = """, "sensitive": { "level": "display" }""")
        val failure = assertThrows<Exception> { start(config, "bank.accounts" to looser).close() }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }
            .any { "looser than its source state.owner of default.$ACCOUNT_FLAT" in it }.assert().isTrue()
    }

    @Test
    fun `a dataset over a table of no state should be unavailable, until it declares none`(@TempDir config: Path) {
        start(
            config,
            "bank.ledger" to accountsFile("ledger"),
            "bank.ledger-none" to accountsFile("ledger", lineage = "none"),
        ).use { host ->
            val unknown = host.post("/wow/dataset/bank/ledger/query", LIST)
            unknown.status.assert().isEqualTo(409)
            unknown.body!!["code"].asString().assert().isEqualTo(QueryErrors.DATASET_UNAVAILABLE)
            unknown.body!!["detail"].asString().assert().contains("lineage")
            val none = host.post("/wow/dataset/bank/ledger-none/query", LIST)
            none.status.assert().isEqualTo(200)
            none.body!![0]["owner"].asString().assert().isEqualTo("Alice Smith")
        }
    }
}
