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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.postgres.query.PostgresSnapshotSqlCompiler.Companion.keepsState
import me.ahoo.wow.query.schema.SnapshotFields
import tools.jackson.databind.node.ObjectNode
import java.sql.ResultSet

/**
 * How one kind of query table is read (decision D235): the snapshot table of an aggregate type ([SNAPSHOT], D161) or
 * its log table ([PostgresEventQueryLayout.EVENTS], D162). The SQL compilers and the backend share everything else.
 *
 * - [expressions]: the column and SQL expression of each field;
 * - [select]: the `SELECT` list a row of a [Projection] needs;
 * - [tieBreakers]: the physical fields, with their columns, that end a list's and an offset page's order so it is
 *   total (each not already sorted by), and that order the rows of an `ANY`, `FIRST` or `LAST` metric;
 * - [record]: the record of one row read by [select].
 */
internal class PostgresQueryLayout(
    val expressions: PostgresQueryExpressions,
    val select: (Projection) -> String,
    val tieBreakers: List<Pair<String, String>>,
    val record: (ResultSet, Projection) -> ObjectNode,
) {
    companion object {
        /** The snapshot table: by aggregate id, then tenant; the state only read when the projection keeps it. */
        val SNAPSHOT: PostgresQueryLayout = PostgresQueryLayout(
            expressions = PostgresSnapshotQueryExpressions,
            select = PostgresSnapshotSqlCompiler::select,
            tieBreakers = listOf(
                SnapshotFields.AGGREGATE_ID to "aggregate_id",
                SnapshotFields.TENANT_ID to "tenant_id"
            ),
            record = { row, projection -> PostgresQueryBackend.record(row, projection.keepsState()) },
        )
    }
}
