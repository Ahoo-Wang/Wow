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

import com.clickhouse.client.api.Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSource
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceDrafted
import me.ahoo.wow.analytics.catalog.api.source.SourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceRevised
import me.ahoo.wow.analytics.catalog.api.source.SourceSensitivityReviewed
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetCompilation
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetCompiler
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * A dataset definition's `containsIgnoreCase`, `startsWithIgnoreCase` and `endsWithIgnoreCase` are the query
 * protocol's ignore-case `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` (`DatasetExpression`'s KDoc), which #6886 made
 * compare both texts after Unicode simple case folding on every backend, ClickHouse's included (D479 ⑤, D480 ④): no
 * answer depends on a store's own folding. So `λόγος` contains `σ` (its final `ς` folds to `σ`), `5µm` contains `μ`
 * (the micro sign folds to `μ`), and `Maſſe` starts with `mas` (`ſ` folds to `s`), as a query of the same rows answers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2DatasetIgnoreCaseTest {
    private val container = WowContainers.clickHouse
    private val client: Client = Client.Builder()
        .addEndpoint(container.httpUrl)
        .setUsername(container.username)
        .setPassword(container.password)
        .build()

    init {
        execute("CREATE DATABASE IF NOT EXISTS ${DatasetCompiler.DEFAULT_DATABASE}")
        execute("DROP DATABASE IF EXISTS $DATABASE")
        execute("CREATE DATABASE $DATABASE")
        execute(
            "CREATE TABLE $DATABASE.notes (tenant String, id String, note String) " +
                "ENGINE = MergeTree ORDER BY (tenant, id)",
        )
        execute(
            // `ascii` is the control: it matches under any folding, so the view itself serves its rows.
            "INSERT INTO $DATABASE.notes VALUES ('t1', 'ascii', 'MASS'), ('t1', 'greek', 'λόγος'), " +
                "('t1', 'micro', '5µm'), ('t1', 'long-s', 'Maſſe'), ('t1', 'plain', 'plain')",
        )
    }

    @AfterAll
    fun close() {
        client.close()
    }

    private fun execute(sql: String) {
        client.execute(sql).get(1, TimeUnit.MINUTES).close()
    }

    @Test
    fun `an ignore-case text test of a definition folds as the query protocol's does`() {
        val definition = DatasetDefinition(
            sources = listOf(DatasetSource("n", NOTES_ID)),
            subject = "n",
            columns = listOf(DatasetColumn("note_id", from = "n.id"), DatasetColumn("note", from = "n.note")),
            filters = listOf(
                "containsIgnoreCase(n.note, 'σ') or containsIgnoreCase(n.note, 'μ') or " +
                    "startsWithIgnoreCase(n.note, 'mas')",
            ),
            identity = "note_id",
        )
        val compiled = DatasetCompiler.compile(definition, mapOf(NOTES_ID to NOTES), "shop", "r2_notes", 1)
        val artifact = (compiled as DatasetCompilation.Compiled).artifact
        execute(artifact.ddl)

        client.queryAll("SELECT note_id FROM ${artifact.database}.${artifact.view} ORDER BY note_id")
            .map { it.getString("note_id") }
            .assert().containsExactly("ascii", "greek", "long-s", "micro")
    }

    private companion object {
        const val DATABASE = "wow_dataset_r2"
        val NOTES_ID = AnalyticsCatalogContext.sourceIdOf("warehouse", DATABASE, "notes")
        val NOTES: SourceState = SourceState().apply {
            val columns = listOf("tenant", "id", "note").map { SourceColumn(it, "String") }
            onSourcing(SourceDrafted("warehouse", "clickhouse", DATABASE, "notes", "u", "MergeTree", columns))
            onSourcing(SourceRevised("tenant", platform = false))
            onSourcing(SourceSensitivityReviewed(columns.map { FieldSensitivity(it.name) }))
            onSourcing(SourceReviewed)
        }
    }
}
