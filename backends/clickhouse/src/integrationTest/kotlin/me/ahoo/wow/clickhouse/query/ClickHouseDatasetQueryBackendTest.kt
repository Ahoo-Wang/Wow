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

package me.ahoo.wow.clickhouse.query

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.DatasetDateBucketCases.Companion.BEFORE_1970_PARTIAL_CHANGE
import me.ahoo.wow.query.tck.DatasetDateBucketCases.Companion.MONROVIA_CHANGE_DAY
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.MICROS
import me.ahoo.wow.query.tck.DatasetTable
import me.ahoo.wow.query.tck.SnapshotDateBucketCases.Companion.GAP_MID_UNIT
import me.ahoo.wow.query.tck.SnapshotDateBucketCases.Companion.GAP_START
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/** The dataset TCK on ClickHouse. */
class ClickHouseDatasetQueryBackendTest : DatasetQueryBackendSpec() {
    override fun createDatasetBindings(): QueryBindings<QueryDataset> =
        ClickHouseDatasetBindings(ClickHouseContainers.client)

    override suspend fun createTable(table: DatasetTable): ObjectNode {
        ClickHouseContainers.load(table)
        return WowJson.mapper.createObjectNode().put("table", "${ClickHouseContainers.DATABASE}.${table.name}")
    }

    /**
     * The 9.x divergences, all still in force: a NULL is a table's only missing value (`AbsentValues.AS_MISSING`), so
     * EXISTS cannot tell an explicit null from a missing field, and an array column stores a missing or null value as
     * an empty array, so presence and emptiness cannot tell the three apart; both as on Elasticsearch. Exact array
     * equality is not supported (`arrayEquality` NONE).
     */
    @Test
    fun `a date position of another form than the instant's text is the invalid cursor`(): Unit = runBlocking {
        val micros = target(MICROS)
        val query = CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("created_at"), Sort.Direction.ASC)), size = 1)
        val admitted = QueryAdmission.Trusted.cursor(query, micros.schema)
        // A millisecond epoch, the form this backend wrote before its positions kept their precision.
        val failure = runCatching {
            micros.backend.page(admitted, PageWindow.Keyset(CursorPosition(listOf(1_767_225_600_000L, "u1")), 1))
        }.exceptionOrNull()
        (failure as QueryRequestException).violation.assert().isEqualTo(QueryViolation.InvalidCursor)
    }

    override val semanticDivergences: Set<String> = setOf(
        "string.exists",
        "string.not-exists",
        "array.is-empty",
        "array.is-null",
        "array.is-not-null",
        "array.exists",
        "array.not-exists",
        "array.eq-array",
        // The documented limit: before 1970 the zone's hours keep ClickHouse's truncation (WHOLE_SHIFTS_SINCE).
        BEFORE_1970_PARTIAL_CHANGE,
        // ClickHouse 24.8's own zone data reads Monrovia's offset for the rest of 1972-01-07 as -00:14:30, not UTC: the
        // hour and the minute whose local start that change skipped start where that offset puts them.
        MONROVIA_CHANGE_DAY,
        GAP_START,
        GAP_MID_UNIT,
    )
}
