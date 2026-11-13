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

package me.ahoo.wow.benchmark.transfer.raw

import java.sql.Connection
import java.sql.SQLException

/**
 * A snapshot table's pages as they are, a diagnostic of the HOT share (an update stays on its page only while the
 * page has room for the new version): its pages, live rows a page, a row's mean width, free bytes a page and dead rows,
 * read with pgstattuple; empty when the extension is not there. [suffix] names when it was read (`Before`, `After`).
 */
fun layoutOf(connection: Connection, table: String, suffix: String): Map<String, Double> = try {
    connection.createStatement().use { it.execute("CREATE EXTENSION IF NOT EXISTS pgstattuple") }
    connection.prepareStatement(
        "SELECT table_len, tuple_count, tuple_len, dead_tuple_count, free_space FROM pgstattuple(?::regclass)",
    ).use { statement ->
        statement.setString(1, table)
        statement.executeQuery().use { r ->
            r.next()
            val pages = r.getDouble(1) / PAGE_BYTES
            val rows = r.getDouble(2)
            if (pages <= 0 || rows <= 0) {
                emptyMap()
            } else {
                mapOf(
                    "$PAGES$suffix" to pages,
                    "$ROWS_PER_PAGE$suffix" to rows / pages,
                    "$ROW_WIDTH$suffix" to r.getDouble(3) / rows,
                    "$FREE_PER_PAGE$suffix" to r.getDouble(5) / pages,
                    "$DEAD_ROWS$suffix" to r.getDouble(4),
                )
            }
        }
    }
} catch (e: SQLException) {
    System.err.println("raw: no layout of $table: ${e.message}")
    emptyMap()
}

/** The layout's names before and after a window, both sides', as the calibration reports them apart. */
val LAYOUT_METRICS = listOf("Before", "After").flatMap { suffix ->
    listOf(PAGES, ROWS_PER_PAGE, ROW_WIDTH, FREE_PER_PAGE, DEAD_ROWS).map { it + suffix }
}

private const val PAGE_BYTES = 8192.0
private const val PAGES = "snapshotPages"
private const val ROWS_PER_PAGE = "snapshotRowsPerPage"
private const val ROW_WIDTH = "snapshotRowWidth"
private const val FREE_PER_PAGE = "snapshotFreePerPage"
private const val DEAD_ROWS = "snapshotDeadRows"
