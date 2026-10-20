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

package me.ahoo.wow.analytics.catalog.domain.source

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.source.ApproveSensitivityChange
import me.ahoo.wow.analytics.catalog.api.source.DraftSource
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.MarkSourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.ReviewSourceSensitivity
import me.ahoo.wow.analytics.catalog.api.source.ReviseSource
import me.ahoo.wow.analytics.catalog.api.source.SensitivityChangeApproved
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceDrafted
import me.ahoo.wow.analytics.catalog.api.source.SourceKind
import me.ahoo.wow.analytics.catalog.api.source.SourceReference
import me.ahoo.wow.analytics.catalog.api.source.SourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceRevised
import me.ahoo.wow.analytics.catalog.api.source.SourceSensitivityReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceStatus
import me.ahoo.wow.analytics.catalog.api.source.SourceTableChanged
import me.ahoo.wow.analytics.catalog.api.source.SourceTableMissing
import me.ahoo.wow.analytics.catalog.api.source.SyncSource
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentAdopted
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentLost
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentRefused
import me.ahoo.wow.analytics.catalog.domain.FakeCatalog
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.CARD
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.ORDERS
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.ORDERS_ID
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.ORDERS_TABLE
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.PHONE
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.STATE
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.STATE_ID
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.STATE_TABLE
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.stateRow
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.analytics.catalog.domain.warehouse.TrustedWriters
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

class SourceSpec {
    private val catalog = FakeCatalog().apply {
        put(ORDERS_TABLE)
        put(STATE_TABLE)
    }
    private val writers = TrustedWriters.of(mapOf(SourceFixtures.SINK to listOf("shop")))
    private val known = KnownAggregates { context, aggregate -> context == "crm" && aggregate == "member" }

    private fun spec(id: String, block: AggregateSpec<SourceState>.() -> Unit) = aggregateSpec(
        sourceModel(AnalyticsCatalogContext.NAME, catalog, writers, known),
        id = id,
        tenantId = AnalyticsCatalogContext.TENANT_ID,
        block = block,
    )

    private fun AggregateSpec<SourceState>.drafted() {
        whenCommand(DraftSource(FakeCatalog.DATABASE, ORDERS)) { expectEventTypes(SourceDrafted::class) }
    }

    private fun AggregateSpec<SourceState>.adopted() {
        whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) { expectEventTypes(WowEnrichmentAdopted::class) }
    }

    @Test
    fun `an analyst's table should be drafted, revised, reviewed column by column and marked reviewed`() =
        spec(ORDERS_ID) {
            whenCommand(DraftSource(FakeCatalog.DATABASE, ORDERS)) {
                expectEvents(
                    SourceDrafted(
                        FakeCatalog.CONNECTION,
                        "clickhouse",
                        FakeCatalog.DATABASE,
                        ORDERS,
                        "uuid-orders",
                        "MergeTree",
                        ORDERS_TABLE.columns,
                    ),
                )
                expectState { kind.assert().isEqualTo(SourceKind.ANALYST) }
            }
            whenCommand(MarkSourceReviewed) {
                expectRejected(SourceRejections.INCOMPLETE) {
                    message.assert().contains("no tenant column")
                }
            }
            val reference = SourceReference("member_id", "crm", "member")
            whenCommand(ReviseSource("tenant", ownerColumn = "owner", references = listOf(reference))) {
                expectEvents(SourceRevised("tenant", "owner", null, listOf(reference)))
            }
            whenCommand(ReviseSource("tenant", ownerColumn = "owner", references = listOf(reference))) {
                expectUnchanged()
            }
            whenCommand(MarkSourceReviewed) {
                expectRejected(SourceRejections.INCOMPLETE) {
                    message.assert().contains("[tenant, owner, phone, member_id]")
                }
            }
            val reviewed = listOf(
                FieldSensitivity("tenant"),
                FieldSensitivity("owner"),
                FieldSensitivity("phone", SensitivityLevel.DISPLAY, keepPrefix = 3),
                FieldSensitivity("member_id"),
            )
            whenCommand(ReviewSourceSensitivity(reviewed)) { expectEvents(SourceSensitivityReviewed(reviewed)) }
            whenCommand(ReviewSourceSensitivity(reviewed)) { expectUnchanged() }
            whenCommand(MarkSourceReviewed) {
                expectEvents(SourceReviewed)
                expectState { status.assert().isEqualTo(SourceStatus.REVIEWED) }
            }
            whenCommand(MarkSourceReviewed) { expectUnchanged() }
            // A tighter phone takes the source back to its draft.
            val tighter = FieldSensitivity("phone", SensitivityLevel.CONFIDENTIAL)
            whenCommand(ReviewSourceSensitivity(listOf(tighter))) {
                expectEvents(SourceSensitivityReviewed(listOf(tighter), reopened = true))
                expectState {
                    status.assert().isEqualTo(SourceStatus.DRAFT)
                    sensitivityOf("phone").assert().isEqualTo(tighter)
                }
            }
            whenCommand(MarkSourceReviewed) { expectEvents(SourceReviewed) }
            whenCommand(ReviseSource("tenant")) {
                expectEvents(SourceRevised("tenant", reopened = true))
                expectState { status.assert().isEqualTo(SourceStatus.DRAFT) }
            }
        }

    @Test
    fun `a draft should be refused for another id, a table not there, or a Wow table`() {
        spec("warehouse:analytics:other") {
            whenCommand(DraftSource(FakeCatalog.DATABASE, ORDERS)) { expectRejected(SourceRejections.INVALID) }
        }
        spec(AnalyticsCatalogContext.sourceIdOf(FakeCatalog.CONNECTION, FakeCatalog.DATABASE, "nothing")) {
            whenCommand(DraftSource(FakeCatalog.DATABASE, "nothing")) {
                expectRejected(SourceRejections.TABLE_NOT_FOUND)
            }
        }
        spec(AnalyticsCatalogContext.sourceIdOf(FakeCatalog.CONNECTION, "other_db", ORDERS)) {
            whenCommand(DraftSource("other_db", ORDERS)) { expectRejected(SourceRejections.TABLE_NOT_FOUND) }
        }
        catalog.rows += stateRow()
        spec(STATE_ID) {
            whenCommand(DraftSource(FakeCatalog.DATABASE, STATE)) { expectRejected(SourceRejections.WOW_SOURCE) }
        }
    }

    @Test
    fun `a revision should name the table's columns and aggregates the catalog knows`() = spec(ORDERS_ID) {
        drafted()
        whenCommand(ReviseSource("tenant_x")) { expectRejected(SourceRejections.INVALID) }
        whenCommand(ReviseSource("tenant", spaceColumn = "space")) { expectRejected(SourceRejections.INVALID) }
        whenCommand(ReviseSource("tenant", references = listOf(SourceReference("member_id", "shop", "store")))) {
            expectRejected(SourceRejections.UNKNOWN_AGGREGATE)
        }
        val twice = listOf(SourceReference("member_id", "crm", "member"), SourceReference("member_id", "crm", "member"))
        whenCommand(ReviseSource("tenant", references = twice)) { expectRejected(SourceRejections.INVALID) }
    }

    @Test
    fun `an analyst's review should be by column, once each, keeping no negative part`() = spec(ORDERS_ID) {
        drafted()
        whenCommand(ReviewSourceSensitivity(emptyList())) { expectRejected(SourceRejections.INVALID) }
        whenCommand(ReviewSourceSensitivity(listOf(FieldSensitivity("nope")))) {
            expectRejected(SourceRejections.INVALID)
        }
        whenCommand(ReviewSourceSensitivity(listOf(FieldSensitivity("phone", event = "e", revision = 1)))) {
            expectRejected(SourceRejections.INVALID)
        }
        whenCommand(ReviewSourceSensitivity(listOf(FieldSensitivity("phone"), FieldSensitivity("phone")))) {
            expectRejected(SourceRejections.INVALID)
        }
        whenCommand(ReviewSourceSensitivity(listOf(FieldSensitivity("phone", keepPrefix = -1)))) {
            expectRejected(SourceRejections.INVALID)
        }
    }

    @Test
    fun `a sync should adopt the trusted row of a Wow table, and change nothing while it holds`() = spec(STATE_ID) {
        catalog.rows += stateRow()
        whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) {
            expectEventTypes(WowEnrichmentAdopted::class)
            expectState {
                kind.assert().isEqualTo(SourceKind.WOW)
                status.assert().isEqualTo(SourceStatus.ADOPTED)
                tenantColumn.assert().isEqualTo("tenant_id")
                ownerColumn.assert().isEqualTo("owner_id")
                references.assert().containsExactly(SourceReference("state.memberId", "crm", "member"))
                enrichment!!.writer.assert().isEqualTo(SourceFixtures.SINK)
                // The raw state column holds the highest level of the values below it.
                sensitivity.assert().containsExactlyInAnyOrder(
                    PHONE,
                    CARD,
                    FieldSensitivity("state", SensitivityLevel.CONFIDENTIAL),
                )
                pending.assert().isEmpty()
            }
        }
        whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) { expectUnchanged() }
        // An analyst only tightens a Wow source, and does not revise it.
        whenCommand(ReviewSourceSensitivity(listOf(PHONE.copy(level = null)))) {
            expectRejected(SourceRejections.LOOSENING)
        }
        val tighter = PHONE.copy(level = SensitivityLevel.CONFIDENTIAL)
        whenCommand(
            ReviewSourceSensitivity(listOf(tighter))
        ) { expectEvents(SourceSensitivityReviewed(listOf(tighter))) }
        whenCommand(ReviseSource("tenant_id")) { expectRejected(SourceRejections.WOW_SOURCE) }
        whenCommand(MarkSourceReviewed) { expectRejected(SourceRejections.WOW_SOURCE) }
        // A newer row of the same claim keeps the analyst's tightening.
        catalog.rows += stateRow(fingerprint = "fingerprint-2", writtenAt = "2026-10-19 11:00:00.000")
        whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) {
            expectEventTypes(WowEnrichmentAdopted::class)
            expectState {
                enrichment!!.fingerprint.assert().isEqualTo("fingerprint-2")
                sensitivityOf(PHONE.path).assert().isEqualTo(tighter)
            }
        }
    }

    @Test
    fun `a row that lowers or drops a value should leave it as it was, pending a privileged approval`() =
        spec(STATE_ID) {
            catalog.rows += stateRow()
            adopted()
            catalog.rows += stateRow(sensitive = listOf(PHONE), fingerprint = "lower", writtenAt = "2026-10-19 12:00:00.000")
            whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) {
                expectEventTypes(WowEnrichmentAdopted::class)
                expectState {
                    sensitivityOf(CARD.path).assert().isEqualTo(CARD)
                    sensitivityOf("state")!!.level.assert().isEqualTo(SensitivityLevel.CONFIDENTIAL)
                    pending.assert().containsExactlyInAnyOrder(CARD.path, "state")
                }
            }
            whenCommand(ApproveSensitivityChange(listOf("state.nothing"))) { expectRejected(SourceRejections.INVALID) }
            whenCommand(ApproveSensitivityChange(listOf(CARD.path))) {
                expectEventTypes(SensitivityChangeApproved::class)
                expectState {
                    sensitivityOf(CARD.path).assert().isNull()
                    pending.assert().containsExactly("state")
                }
            }
            whenCommand(ApproveSensitivityChange()) {
                expectState {
                    sensitivityOf("state")!!.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
                    pending.assert().isEmpty()
                }
            }
            whenCommand(ApproveSensitivityChange()) { expectUnchanged() }
        }

    @Test
    fun `untrusted rows should be refused for the audit, and a lost row put every value pending`() = spec(STATE_ID) {
        catalog.rows += stateRow()
        adopted()
        catalog.rows.clear()
        catalog.rows += stateRow(writer = "intruder", writtenAt = "2026-10-19 13:00:00.000")
        catalog.rows += stateRow(tableUuid = "uuid-old", writtenAt = "2026-10-19 13:00:00.000")
        whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) {
            expectEventTypes(WowEnrichmentLost::class, WowEnrichmentRefused::class)
            expectState {
                enrichmentLost.assert().isTrue()
                pending.assert().containsExactlyInAnyOrder(PHONE.path, CARD.path, "state")
                sensitivityOf(CARD.path).assert().isEqualTo(CARD)
                refusals.size.assert().isEqualTo(2)
                refusals.joinToString().assert().contains("intruder is not trusted for shop").contains("uuid-old")
            }
        }
        whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) { expectUnchanged() }
        // The trusted row back: adopted again, nothing lowered against the lost claim.
        catalog.rows += stateRow(writtenAt = "2026-10-19 14:00:00.000")
        whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) {
            expectEventTypes(WowEnrichmentAdopted::class)
            expectState {
                enrichmentLost.assert().isFalse()
                refusals.assert().isEmpty()
            }
        }
    }

    @Test
    fun `a sync of an unknown table should change nothing, untrusted or not there`() {
        spec(STATE_ID) {
            catalog.rows += stateRow(writer = "intruder")
            whenCommand(SyncSource(FakeCatalog.DATABASE, STATE)) { expectUnchanged() }
            whenCommand(SyncSource(FakeCatalog.DATABASE, "other")) { expectRejected(SourceRejections.INVALID) }
        }
        spec(AnalyticsCatalogContext.sourceIdOf(FakeCatalog.CONNECTION, FakeCatalog.DATABASE, "gone")) {
            whenCommand(SyncSource(FakeCatalog.DATABASE, "gone")) { expectUnchanged() }
        }
    }

    @Test
    fun `an analyst's table that changes or goes should follow, back to its draft when it is no longer complete`() =
        spec(ORDERS_ID) {
            drafted()
            whenCommand(ReviseSource("tenant")) { expectEventTypes(SourceRevised::class) }
            val all = ORDERS_TABLE.columns.filter { Source.isString(it.type) }.map { FieldSensitivity(it.name) }
            whenCommand(ReviewSourceSensitivity(all)) { expectEventTypes(SourceSensitivityReviewed::class) }
            whenCommand(MarkSourceReviewed) { expectEvents(SourceReviewed) }
            whenCommand(SyncSource(FakeCatalog.DATABASE, ORDERS)) { expectUnchanged() }
            // A numeric column added: still complete.
            val wider = ORDERS_TABLE.copy(columns = ORDERS_TABLE.columns + SourceColumn("count", "UInt32"))
            catalog.put(wider)
            whenCommand(SyncSource(FakeCatalog.DATABASE, ORDERS)) {
                expectEvents(SourceTableChanged("uuid-orders", "MergeTree", wider.columns))
                expectState { status.assert().isEqualTo(SourceStatus.REVIEWED) }
            }
            // A string column added: not reviewed, so back to the draft.
            val note = wider.copy(columns = wider.columns + SourceColumn("note", "String"))
            catalog.put(note)
            whenCommand(SyncSource(FakeCatalog.DATABASE, ORDERS)) {
                expectEvents(SourceTableChanged("uuid-orders", "MergeTree", note.columns, reopened = true))
                expectState { status.assert().isEqualTo(SourceStatus.DRAFT) }
            }
            catalog.tables.remove("${FakeCatalog.DATABASE}.$ORDERS")
            whenCommand(SyncSource(FakeCatalog.DATABASE, ORDERS)) {
                expectEvents(SourceTableMissing)
                expectState { missing.assert().isTrue() }
            }
            whenCommand(SyncSource(FakeCatalog.DATABASE, ORDERS)) { expectUnchanged() }
            whenCommand(MarkSourceReviewed) { expectRejected(SourceRejections.TABLE_MISSING) }
            catalog.put(note)
            whenCommand(SyncSource(FakeCatalog.DATABASE, ORDERS)) {
                expectEvents(SourceTableChanged("uuid-orders", "MergeTree", note.columns))
                expectState { missing.assert().isFalse() }
            }
        }

    @Test
    fun `an analyst's table that gets a trusted row should become a Wow source, keeping what was tighter`() {
        val table = STATE_TABLE.copy(name = ORDERS, uuid = ORDERS_TABLE.uuid, columns = STATE_TABLE.columns)
        catalog.put(table)
        spec(ORDERS_ID) {
            drafted()
            val tight = FieldSensitivity("state", SensitivityLevel.CONFIDENTIAL)
            whenCommand(ReviewSourceSensitivity(listOf(tight))) { expectEventTypes(SourceSensitivityReviewed::class) }
            catalog.rows += stateRow(sensitive = listOf(PHONE), table = table)
            whenCommand(SyncSource(FakeCatalog.DATABASE, ORDERS)) {
                expectEventTypes(WowEnrichmentAdopted::class)
                expectState {
                    kind.assert().isEqualTo(SourceKind.WOW)
                    sensitivityOf("state").assert().isEqualTo(tight)
                    pending.assert().isEmpty()
                }
            }
        }
    }
}
