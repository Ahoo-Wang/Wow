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

package me.ahoo.wow.datasetregistry.domain.catalog

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.datasetregistry.api.source.SourceColumn
import me.ahoo.wow.datasetregistry.domain.SourceFixtures
import me.ahoo.wow.datasetregistry.domain.SourceFixtures.STATE_TABLE
import me.ahoo.wow.datasetregistry.domain.SourceFixtures.stateRow
import org.junit.jupiter.api.Test

class CatalogTrustTest {
    private val writers = TrustedWriters.of(mapOf(SourceFixtures.SINK to listOf("shop")))

    @Test
    fun `a row should be adopted only from a trusted writer, of the table's uuid and columns`() {
        CatalogTrust.evaluate(STATE_TABLE, emptyList(), writers).assert().isEqualTo(CatalogTrust.Verdict.None)
        CatalogTrust.evaluate(STATE_TABLE, listOf(stateRow(table = STATE_TABLE.copy(name = "x"))), writers)
            .assert().isEqualTo(CatalogTrust.Verdict.None)
        val trusted = stateRow()
        CatalogTrust.evaluate(STATE_TABLE, listOf(trusted), writers).assert()
            .isEqualTo(CatalogTrust.Verdict.Trusted(trusted))
        val refused = CatalogTrust.evaluate(
            STATE_TABLE,
            listOf(
                stateRow(writer = "other"),
                stateRow(tableUuid = "uuid-x", fingerprint = "f2"),
                stateRow(table = STATE_TABLE.copy(columns = listOf(SourceColumn("id", "String"))), fingerprint = "f3"),
            ),
            writers,
        ) as CatalogTrust.Verdict.Refused
        refused.reasons.assert().hasSize(3)
        refused.reasons.joinToString().assert()
            .contains("other is not trusted for shop")
            .contains("uuid-x")
            .contains("other columns")
        // A trusted writer of another context is not trusted for this one.
        CatalogTrust.evaluate(
            STATE_TABLE,
            listOf(trusted),
            TrustedWriters.of(mapOf(SourceFixtures.SINK to listOf("crm")))
        )
            .assert().isInstanceOf(CatalogTrust.Verdict.Refused::class.java)
        CatalogTrust.evaluate(STATE_TABLE, listOf(trusted), TrustedWriters.NONE)
            .assert().isInstanceOf(CatalogTrust.Verdict.Refused::class.java)
    }

    @Test
    fun `of the rows that match the table the latest should win`() {
        val old = stateRow(fingerprint = "old", writtenAt = "2026-10-19 09:00:00.000")
        val new = stateRow(fingerprint = "new", writtenAt = "2026-10-19 10:00:00.000")
        val mismatched = stateRow(fingerprint = "newest", writtenAt = "2026-10-19 11:00:00.000", tableUuid = "uuid-x")
        CatalogTrust.evaluate(STATE_TABLE, listOf(new, mismatched, old), writers).assert()
            .isEqualTo(CatalogTrust.Verdict.Trusted(new))
    }

    @Test
    fun `the columns hash should be the Sink's`() {
        // SHA-256 of "id String\nstate String", as ClickHouseCatalog.columnsHash computes it.
        val expected = java.security.MessageDigest.getInstance(
            "SHA-256"
        ).digest("id String\nstate String".toByteArray())
            .joinToString("") { "%02x".format(it) }
        CatalogTrust.columnsHash(listOf(SourceColumn("id", "String"), SourceColumn("state", "String"))).assert()
            .isEqualTo(expected)
    }

    @Test
    fun `no warehouse should have no table`(): Unit = runBlocking {
        val none = WarehouseCatalog.NONE
        none.connection.assert().isEqualTo("none")
        none.databases.assert().isEmpty()
        none.tables("db").assert().isEmpty()
        none.table("db", "t").assert().isNull()
        none.catalogRows("db", "t").assert().isEmpty()
        none.catalogChanges("db").assert().isEmpty()
    }
}
