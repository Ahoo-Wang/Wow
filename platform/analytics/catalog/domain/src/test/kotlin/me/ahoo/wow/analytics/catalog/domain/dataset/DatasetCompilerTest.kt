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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetField
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetScope
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSensitivity
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSource
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DEFINITION
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.MEMBERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.ORDERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.REGIONS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.SOURCES
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import org.junit.jupiter.api.Test

/**
 * The compiler's SQL, compared with the golden files of `src/test/resources/dataset/`: a change of the SQL a
 * definition compiles to shows in the diff of its golden file.
 */
class DatasetCompilerTest {
    private fun bound(
        definition: DatasetDefinition,
        sources: Map<String, me.ahoo.wow.analytics.catalog.domain.source.SourceState> = SOURCES
    ) =
        (DatasetBinder.bind(definition, sources) as DatasetBinder.Result.Bound).dataset

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/dataset/$name.sql")) { "No golden file $name.sql." }.readText().trimEnd()

    @Test
    fun `a wide table should compile to one view of its version, reading merged sources through FINAL`() {
        val artifact = DatasetCompiler.compile(bound(DEFINITION), "shop", "orders", 3, title = "Orders")
        artifact.ddl.assert().isEqualTo(golden("orders"))
        artifact.view.assert().isEqualTo("ds_shop_orders_v3")
        artifact.database.assert().isEqualTo(DatasetCompiler.DEFAULT_DATABASE)
        artifact.version.assert().isEqualTo(3)
        artifact.title.assert().isEqualTo("Orders")
        artifact.scope.assert().isEqualTo(DatasetScope(tenant = "tenant_id", owner = "owner_id"))
        artifact.sources.assert().containsExactly(ORDERS_ID, MEMBERS_ID, REGIONS_ID)
        artifact.identity.assert().isEqualTo("order_id")
        artifact.eventTime.assert().isEqualTo("month")
        artifact.approvals.assert().isEmpty()
        artifact.fields.first { it.name == "member_phone" }.assert().isEqualTo(
            DatasetField(
                name = "member_phone",
                sensitivity = DatasetSensitivity(SensitivityLevel.DISPLAY, keepPrefix = 3, keepSuffix = 4),
                inputs = listOf("$MEMBERS_ID/state.customer.phone"),
            ),
        )
        artifact.fields.first { it.name == "month" }.assert()
            .isEqualTo(DatasetField("month", inputs = listOf("$ORDERS_ID/placed_at"), computed = true))
    }

    @Test
    fun `expressions should compile with every name quoted and every text escaped`() {
        val definition = DEFINITION.copy(
            sources = DEFINITION.sources.take(1),
            joins = emptyList(),
            columns = listOf(
                DatasetColumn("label", expression = "concat(o.region, ' \\ it''s `x`', toString(-o.amount % 3))"),
                DatasetColumn("big", expression = "if(o.amount >= 100 and not o.phone is null, true, false)"),
                DatasetColumn(
                    "kind",
                    expression = "multiIf(o.region in ('n', 's'), 1, o.region not in ('e'), 2.5, null)"
                ),
                DatasetColumn("`odd`", from = "o.member_id"),
            ),
            filters = listOf("o.region is not null or o.amount / 2 < 10", "o.phone != ''"),
            identity = null,
            eventTime = null,
        )
        val problems = DatasetBinder.bind(definition, SOURCES) as DatasetBinder.Result.Invalid
        problems.problems.single().assert().contains("`odd` is not a name")
        val artifact = DatasetCompiler.compile(
            bound(definition.copy(columns = definition.columns.dropLast(1))),
            "shop",
            "labels",
            1
        )
        artifact.ddl.assert().isEqualTo(golden("labels"))
        artifact.approvals.assert().containsExactly("The column big computes from the sensitive values [o.phone].")
    }

    @Test
    fun `a platform dimension should compile to a view without a tenant column`() {
        val definition = DatasetDefinition(
            sources = listOf(DatasetSource("r", REGIONS_ID)),
            subject = "r",
            columns = listOf(DatasetColumn("code", from = "r.code"), DatasetColumn("name", from = "r.name")),
        )
        val artifact = DatasetCompiler.compile(bound(definition), "ops", "regions", 1, database = "bi")
        artifact.ddl.assert().isEqualTo(golden("regions"))
        artifact.scope.assert().isEqualTo(DatasetScope(platform = true))
    }

    @Test
    fun `a value inside a JSON column should read its subcolumn`() {
        val members = DatasetFixtures.analyst(
            "events",
            listOf(SourceColumn("tenant", "String"), SourceColumn("doc", "JSON")),
            tenant = "tenant",
        )
        val definition = DatasetDefinition(
            sources = listOf(DatasetSource("e", "events")),
            subject = "e",
            columns = listOf(DatasetColumn("city", from = "e.doc.address.city")),
        )
        DatasetCompiler.compile(bound(definition, mapOf("events" to members)), "shop", "events", 1).ddl.assert()
            .contains("`e`.`doc`.`address`.`city` AS `city`")
    }

    @Test
    fun `a definition should compile from the catalog's sources, or say why it does not`() {
        (DatasetCompiler.compile(DEFINITION, SOURCES, "shop", "orders", 3) as DatasetCompilation.Compiled)
            .artifact.ddl.assert().isEqualTo(golden("orders"))
        (
            DatasetCompiler.compile(
                DEFINITION.copy(subject = "x"),
                SOURCES,
                "shop",
                "orders",
                3
            ) as DatasetCompilation.Invalid
            )
            .problems.assert().containsExactly("The subject x is not an alias of the sources.")
    }

    /** Each filter operator of the query protocol, rendered with its semantics there (`ClickHouseFilterCompiler`). */
    @Test
    fun `filters should mean in the view what the query protocol's filters mean`() {
        val notes = DatasetFixtures.analyst(
            "notes",
            listOf(
                SourceColumn("tenant", "String"),
                SourceColumn("a", "Int64"),
                SourceColumn("note", "Nullable(String)")
            ),
            tenant = "tenant",
        )
        val table = listOf(
            "n.a = 1" to "(`n`.`a` = 1)",
            "n.note = null" to "`n`.`note` IS NULL",
            "n.note != null" to "`n`.`note` IS NOT NULL",
            "n.a != 1" to "(`n`.`a` != 1)",
            "n.note != 'x'" to "((`n`.`note` != 'x') OR isNull(`n`.`note`))",
            "n.note not in ('x')" to "(`n`.`note` NOT IN ('x') OR isNull(`n`.`note`))",
            "n.a not in (1)" to "`n`.`a` NOT IN (1)",
            "lower(n.note) not in ('x')" to "lower(`n`.`note`) NOT IN ('x')",
            "n.a in (1, 2)" to "`n`.`a` IN (1, 2)",
            "n.a between 1 and 2" to "(`n`.`a` BETWEEN 1 AND 2)",
            "contains(n.note, 'x')" to "(position(`n`.`note`, 'x') > 0)",
            "containsIgnoreCase(n.note, 'x')" to "(positionCaseInsensitiveUTF8(`n`.`note`, 'x') > 0)",
            "startsWith(n.note, 'x')" to "startsWith(`n`.`note`, 'x')",
            "startsWithIgnoreCase(n.note, 'x')" to "startsWith(lowerUTF8(`n`.`note`), lowerUTF8('x'))",
            "endsWith(n.note, 'x')" to "endsWith(`n`.`note`, 'x')",
            "endsWithIgnoreCase(n.note, 'x')" to "endsWith(lowerUTF8(`n`.`note`), lowerUTF8('x'))",
        )
        table.forEach { (filter, sql) ->
            val definition = DatasetDefinition(
                sources = listOf(DatasetSource("n", "notes")),
                subject = "n",
                columns = listOf(DatasetColumn("a", from = "n.a")),
                filters = listOf(filter),
            )
            DatasetCompiler.compile(bound(definition, mapOf("notes" to notes)), "shop", "notes", 1).ddl.assert()
                .describedAs(filter).endsWith("WHERE ($sql)")
        }
    }

    @Test
    fun `a left join's values should keep their nulls under != and not in`() {
        val definition = DEFINITION.copy(filters = listOf("m.state.customer.name not in ('x')", "m.id != 'y'"))
        DatasetCompiler.compile(bound(definition), "shop", "orders", 1).ddl.assert().contains(
            "WHERE ((JSONExtractString(`m`.`state`, 'customer', 'name') NOT IN ('x') OR " +
                "isNull(JSONExtractString(`m`.`state`, 'customer', 'name')))) AND " +
                "(((`m`.`id` != 'y') OR isNull(`m`.`id`)))",
        )
    }

    @Test
    fun `a sensitive value that is not text should be shown by its text, so it can be masked`() {
        val definition = DEFINITION.copy(
            columns = listOf(
                DatasetColumn("phone", from = "o.phone"),
                DatasetColumn("member_phone", from = "m.state.customer.phone"),
                DatasetColumn(
                    "amount",
                    from = "o.amount",
                    sensitivity = DatasetSensitivity(SensitivityLevel.CONFIDENTIAL)
                ),
                DatasetColumn("masked", expression = "concat(o.phone, 'x')"),
                DatasetColumn("plain", from = "o.amount"),
            ),
            identity = null,
            eventTime = null,
        )
        val ddl = DatasetCompiler.compile(bound(definition), "shop", "orders", 1).ddl
        ddl.assert().contains("`o`.`phone` AS `phone`")
            .contains("JSONExtractString(`m`.`state`, 'customer', 'phone') AS `member_phone`")
            .contains("toString(`o`.`amount`) AS `amount`")
            .contains("toString(concat(`o`.`phone`, 'x')) AS `masked`")
            .contains("`o`.`amount` AS `plain`")
        val json = DatasetFixtures.analyst(
            "events",
            listOf(SourceColumn("tenant", "String"), SourceColumn("doc", "JSON")),
            tenant = "tenant",
            sensitivity = listOf(
                me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity("doc", SensitivityLevel.DISPLAY)
            ),
        )
        val inside = DatasetDefinition(
            sources = listOf(DatasetSource("e", "events")),
            subject = "e",
            columns = listOf(DatasetColumn("city", from = "e.doc.address.city")),
        )
        DatasetCompiler.compile(bound(inside, mapOf("events" to json)), "shop", "events", 1).ddl.assert()
            .contains("toString(`e`.`doc`.`address`.`city`) AS `city`")
    }

    @Test
    fun `a view's name should be safe and its version positive`() {
        DatasetCompiler.viewOf("shop-ops", "Orders", 12).assert().isEqualTo("ds_shop_ops_orders_v12")
        assertThrownBy<IllegalArgumentException> { DatasetCompiler.compile(bound(DEFINITION), "shop", "orders", 0) }
        DatasetCompiler.quote("a`b\\c").assert().isEqualTo("`a\\`b\\\\c`")
        DatasetCompiler.literal("it's \\").assert().isEqualTo("'it\\'s \\\\'")
    }
}
