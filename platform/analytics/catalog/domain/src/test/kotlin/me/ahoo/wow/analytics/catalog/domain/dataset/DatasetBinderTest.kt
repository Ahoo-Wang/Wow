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

package me.ahoo.wow.analytics.catalog.domain.dataset

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoin
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKey
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKind
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetReference
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSensitivity
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSource
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceTableMissing
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DEFINITION
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DRAFT_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.MEMBERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.ORDERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.REGIONS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.SOURCES
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import org.junit.jupiter.api.Test

class DatasetBinderTest {
    private fun bound(definition: DatasetDefinition, sources: Map<String, SourceState?> = SOURCES): BoundDataset =
        (DatasetBinder.bind(definition, sources) as DatasetBinder.Result.Bound).dataset

    private fun problems(definition: DatasetDefinition, sources: Map<String, SourceState?> = SOURCES): List<String> =
        (DatasetBinder.bind(definition, sources) as DatasetBinder.Result.Invalid).problems

    private fun column(name: String, from: String? = null, expression: String? = null, sensitivity: DatasetSensitivity? = null) =
        DEFINITION.copy(columns = DEFINITION.columns + DatasetColumn(name, from, expression, sensitivity = sensitivity))

    @Test
    fun `a definition should bind its sources and joins, each column protected as its lineage says`() {
        val dataset = bound(DEFINITION)
        dataset.subject.id.assert().isEqualTo(ORDERS_ID)
        dataset.sources.map { it.id }.assert().containsExactly(ORDERS_ID, MEMBERS_ID, REGIONS_ID)
        dataset.joins.map { it.kind }.assert().containsExactly(DatasetJoinKind.LEFT, DatasetJoinKind.INNER)
        dataset.platform.assert().isFalse()
        dataset.identity.assert().isEqualTo("order_id")
        dataset.eventTime.assert().isEqualTo("month")
        dataset.filters.assert().hasSize(1)
        dataset.approvals.assert().isEmpty()
        val columns = dataset.columns.associateBy { it.name }
        // A value keeps its source's mask; a value inside a JSON column, the mask of that value.
        columns.getValue("phone").sensitivity.assert().isEqualTo(DatasetSensitivity(SensitivityLevel.DISPLAY, 3, 0))
        columns.getValue("phone").title.assert().isEqualTo("Phone")
        columns.getValue("member_phone").sensitivity.assert()
            .isEqualTo(DatasetSensitivity(SensitivityLevel.DISPLAY, keepPrefix = 3, keepSuffix = 4))
        columns.getValue("region").sensitivity.assert().isNull()
        columns.getValue("month").computed.assert().isTrue()
        columns.getValue("month").sensitivity.assert().isNull()
    }

    @Test
    fun `a value holding sensitive ones should take their highest level, fully masked`() {
        val columns = bound(
            DEFINITION.copy(
                columns = listOf(
                    DatasetColumn("customer", from = "m.state.customer"),
                    DatasetColumn("state", from = "m.state"),
                    DatasetColumn("card", from = "m.state.card.number"),
                ),
                identity = null,
                eventTime = null,
            ),
        ).columns.associateBy { it.name }
        columns.getValue("customer").sensitivity.assert().isEqualTo(DatasetSensitivity(SensitivityLevel.DISPLAY))
        columns.getValue("state").sensitivity.assert().isEqualTo(DatasetSensitivity(SensitivityLevel.CONFIDENTIAL))
        columns.getValue("card").sensitivity.assert().isEqualTo(DatasetSensitivity(SensitivityLevel.CONFIDENTIAL))
    }

    @Test
    fun `a computed column of sensitive values, or a lower protection than the lineage's, should take an approver`() {
        val computed = bound(column("masked", expression = "concat(substring(o.phone, 1, 3), '****')"))
        computed.columns.last().sensitivity.assert().isEqualTo(DatasetSensitivity(SensitivityLevel.DISPLAY))
        computed.approvals.single().assert().contains("masked computes from the sensitive values [o.phone]")
        val lowered = bound(column("open_phone", from = "o.phone", sensitivity = DatasetSensitivity()))
        lowered.columns.last().sensitivity.assert().isNull()
        lowered.approvals.single().assert().contains("given no protection, lower than its lineage's DISPLAY")
        val tighter = DatasetSensitivity(SensitivityLevel.CONFIDENTIAL, keepSuffix = 2)
        val tightened = bound(column("hidden_phone", from = "o.phone", sensitivity = tighter))
        tightened.columns.last().sensitivity.assert().isEqualTo(tighter)
        tightened.approvals.assert().isEmpty()
        val confidential =
            bound(column("amount_level", from = "o.amount", sensitivity = DatasetSensitivity(SensitivityLevel.DISPLAY)))
        confidential.columns.last().sensitivity.assert().isEqualTo(DatasetSensitivity(SensitivityLevel.DISPLAY))
    }

    @Test
    fun `a platform dimension as the subject should join platform dimensions only`() {
        val regions = DatasetDefinition(
            sources = listOf(DatasetSource("r", REGIONS_ID), DatasetSource("o", ORDERS_ID)),
            subject = "r",
            joins = listOf(DatasetJoin("o", listOf(DatasetJoinKey("r.code", "o.region")))),
            columns = listOf(DatasetColumn("name", from = "r.name")),
        )
        problems(regions).single().assert().contains("brings a tenant's rows into a platform dimension's")
        val alone = bound(regions.copy(sources = regions.sources.take(1), joins = emptyList()))
        alone.platform.assert().isTrue()
    }

    @Test
    fun `a join should key on the tenant of a tenant's source joined before it`() {
        val noTenant = DEFINITION.copy(
            joins = listOf(
                DatasetJoin("m", listOf(DatasetJoinKey("o.member_id", "m.id"))),
                DEFINITION.joins[1],
            ),
        )
        problems(noTenant).single().assert().contains("The join of m keys on no tenant")
        // A platform dimension's column is no tenant to key on.
        val throughRegion = DEFINITION.copy(
            joins = listOf(
                DEFINITION.joins[1],
                DatasetJoin("m", listOf(DatasetJoinKey("r.code", "m.tenant_id"))),
            ),
        )
        problems(throughRegion).single().assert().contains("keys on no tenant")
    }

    @Test
    fun `joins should bring each source in once, after what their keys read`() {
        problems(DEFINITION.copy(joins = DEFINITION.joins.take(1))).assert()
            .containsExactly("The source r is never joined.", "The column region reads r.name before r is joined.")
        problems(DEFINITION.copy(joins = DEFINITION.joins + DEFINITION.joins[1])).assert()
            .containsExactly("The join of r: a source is joined once.")
        problems(
            DEFINITION.copy(joins = DEFINITION.joins + DatasetJoin("o", listOf(DatasetJoinKey("o.tenant", "o.tenant"))))
        )
            .first().assert().isEqualTo("The join of o: the subject is not joined.")
        problems(
            DEFINITION.copy(joins = DEFINITION.joins + DatasetJoin("x", listOf(DatasetJoinKey("o.tenant", "x.tenant"))))
        )
            .assert().contains("The join of x: x is not an alias of the sources.")
        problems(DEFINITION.copy(joins = listOf(DEFINITION.joins[0], DatasetJoin("r", emptyList()))))
            .assert().containsExactly("The join of r has no key.")
        val before = DEFINITION.copy(
            joins = listOf(DatasetJoin("r", listOf(DatasetJoinKey("m.id", "r.code"))), DEFINITION.joins[0]),
        )
        problems(before).assert().contains("The join of r reads m.id before m is joined.")
        val crossed = DEFINITION.copy(
            joins = listOf(DEFINITION.joins[0], DatasetJoin("r", listOf(DatasetJoinKey("o.region", "o.region")))),
        )
        problems(crossed).assert().contains("The join of r reads o.region before o is joined.")
        val path = DEFINITION.copy(
            joins = listOf(
                DatasetJoin(
                    "m",
                    listOf(DatasetJoinKey("o.tenant", "m.tenant_id"), DatasetJoinKey("o.member_id", "m.state.id"))
                ),
                DEFINITION.joins[1],
            ),
        )
        problems(
            path
        ).assert().containsExactly("The join of m keys on o.member_id = m.state.id: a key is a whole column.")
        val expression = DEFINITION.copy(
            joins = listOf(DEFINITION.joins[0], DatasetJoin("r", listOf(DatasetJoinKey("lower(o.region)", "r.code")))),
        )
        problems(expression).assert().containsExactly("The join of r is lower(o.region): a reference <alias>.<column>.")
        val malformed = DEFINITION.copy(
            joins = listOf(DEFINITION.joins[0], DatasetJoin("r", listOf(DatasetJoinKey("o.", "r.code")))),
        )
        problems(malformed).single().assert().contains("ends with a dot")
    }

    @Test
    fun `the sources should be usable, under unique aliases, on one warehouse`() {
        val sources = DEFINITION.sources
        problems(DEFINITION.copy(sources = sources + DatasetSource("d", DRAFT_ID))).assert()
            .contains("The source $DRAFT_ID is a draft: it is reviewed first.")
        problems(DEFINITION.copy(sources = sources + DatasetSource("x", "nothing"))).assert()
            .contains("The catalog has no source nothing.")
        problems(DEFINITION, SOURCES + (MEMBERS_ID to null)).assert().contains("The catalog has no source $MEMBERS_ID.")
        problems(DEFINITION, SOURCES + (MEMBERS_ID to SourceState())).assert()
            .contains("The catalog has no source $MEMBERS_ID.")
        val missing = DatasetFixtures.analyst("regions", listOf(SourceColumn("code", "String")), platform = true)
            .apply { onSourcing(SourceTableMissing) }
        problems(
            DEFINITION,
            SOURCES + (REGIONS_ID to missing)
        ).assert().contains("The source $REGIONS_ID's table is gone.")
        problems(DEFINITION.copy(sources = sources + DatasetSource("o", ORDERS_ID))).assert()
            .contains("The alias o names one source only.")
        problems(DEFINITION.copy(sources = sources + DatasetSource("not-a-name", ORDERS_ID))).assert()
            .contains("The alias not-a-name is not a name: letters, digits and _.")
        val elsewhere = DatasetFixtures.analyst(
            "regions",
            listOf(SourceColumn("code", "String"), SourceColumn("name", "String")),
            platform = true,
            connection = "other",
        )
        problems(DEFINITION, SOURCES + (REGIONS_ID to elsewhere)).single().assert().contains("one warehouse only")
        problems(DEFINITION.copy(sources = emptyList(), joins = emptyList())).assert()
            .contains("A dataset reads one source at least.")
        problems(DEFINITION.copy(subject = "x")).assert().contains("The subject x is not an alias of the sources.")
    }

    @Test
    fun `each column should be a named value of the sources, or an expression of them`() {
        problems(
            column("1st", from = "o.amount")
        ).assert().containsExactly("The column 1st is not a name: letters, digits and _.")
        problems(column("tenant_id", from = "o.tenant")).single().assert().contains("the subject's scope column")
        problems(
            column("amount", from = "o.amount")
        ).assert().containsExactly("The column amount is declared once only.")
        problems(column("both", from = "o.amount", expression = "o.amount")).single().assert()
            .contains("either from a source's value or an expression")
        problems(column("neither")).single().assert().contains("either from a source's value or an expression")
        problems(column("x", from = "o.nothing")).single().assert()
            .isEqualTo("The column x reads o.nothing: nothing is not a column of analytics.orders.")
        problems(
            column("x", from = "o.amount.cents")
        ).single().assert().contains("amount is Decimal(18, 2), not a JSON value")
        problems(column("x", from = "y.amount")).single().assert().contains("y is not an alias of the sources")
        problems(column("x", expression = "sleep(3)")).single().assert().contains("calls sleep")
        problems(column("x", from = "o.amount", sensitivity = DatasetSensitivity(keepPrefix = -1))).single().assert()
            .contains("keeps no negative number")
        problems(DEFINITION.copy(columns = emptyList(), identity = null, eventTime = null)).assert()
            .containsExactly("A dataset shows one column at least.")
        problems(
            DEFINITION.copy(identity = "nothing")
        ).assert().containsExactly("nothing is not a column of the dataset.")
        problems(DEFINITION.copy(filters = listOf("o.nothing = 1", "o.amount >"))).assert().containsExactly(
            "The filter 1 reads o.nothing: nothing is not a column of analytics.orders.",
            "The filter 2: The expression [o.amount >] ends too early.",
        )
    }

    @Test
    fun `a reference to an unusable source should add no problem of its own`() {
        problems(DEFINITION, SOURCES + (MEMBERS_ID to DatasetFixtures.DRAFT)).assert()
            .containsExactly("The source $MEMBERS_ID is a draft: it is reviewed first.")
    }

    @Test
    fun `an unreviewed value should not be sensitive, a strategy mask a full one`() {
        val strategy = DatasetFixtures.analyst(
            "orders",
            DatasetFixtures.ORDERS.columns,
            tenant = "tenant",
            sensitivity = listOf(
                FieldSensitivity("phone", SensitivityLevel.DISPLAY, keepPrefix = 3, strategy = "phone")
            ),
        )
        bound(DEFINITION, SOURCES + (ORDERS_ID to strategy)).columns.first { it.name == "phone" }.sensitivity.assert()
            .isEqualTo(DatasetSensitivity(SensitivityLevel.DISPLAY))
    }

    @Test
    fun `a value a source declares a reference, or a Wow table's aggregate id, should refer to its aggregate`() {
        val orders = DatasetFixtures.analyst(
            "orders",
            DatasetFixtures.ORDERS.columns,
            tenant = "tenant",
            sensitivity = DatasetFixtures.ORDERS.sensitivity,
            references = listOf(me.ahoo.wow.analytics.catalog.api.source.SourceReference("member_id", "crm", "member")),
        )
        val members = DatasetFixtures.MEMBERS.let { wow ->
            SourceState().apply {
                onSourcing(
                    me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentAdopted(
                        connection = wow.connection,
                        engine = wow.engine,
                        database = wow.database,
                        table = wow.table,
                        tableUuid = wow.tableUuid,
                        tableEngine = wow.tableEngine,
                        columns = wow.columns + SourceColumn("aggregate_id", "String"),
                        enrichment = wow.enrichment!!,
                        tenantColumn = "tenant_id",
                        references = listOf(
                            me.ahoo.wow.analytics.catalog.api.source.SourceReference("state.cardId", "pay", "card"),
                        ),
                    ),
                )
            }
        }
        val columns = bound(
            DEFINITION.copy(
                columns = listOf(
                    DatasetColumn("member", from = "o.member_id"),
                    DatasetColumn("member_aggregate", from = "m.aggregate_id"),
                    DatasetColumn("card", from = "m.state.cardId"),
                    DatasetColumn("plain", from = "o.amount"),
                    DatasetColumn("computed", expression = "lower(o.member_id)"),
                ),
                identity = null,
                eventTime = null,
            ),
            SOURCES + (ORDERS_ID to orders) + (MEMBERS_ID to members),
        ).columns.associate { it.name to it.reference }
        columns.assert().isEqualTo(
            mapOf(
                "member" to DatasetReference("crm", "member"),
                "member_aggregate" to DatasetReference("shop", "member"),
                "card" to DatasetReference("pay", "card"),
                "plain" to null,
                "computed" to null,
            ),
        )
    }
}
