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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Holds the access scale check's warehouse (`test/benchmarks/harness/measure/dataset-access.sql`, which
 * `bench.sh measure` loads) to the Sink, as [PublishedDdlTest] holds the published samples: the scale check creates
 * the account's `_state` by hand, without the Sink, so a column the Sink adds (#5957's `access_owner`) is missing
 * there until this fails. The benchmark's account declares no access attribute of its own, and the check reads none of
 * its flat columns, which the file leaves out.
 */
class ScaleCheckDdlTest {
    @Test
    fun `the scale check's _state should be the Sink's, without the history and the flat columns`() {
        val file = File(checkNotNull(System.getProperty(PROPERTY)) { "The build sets $PROPERTY." })
        // Split as the harness splits it (measure.sh: read -d ';'), its comment and blank lines dropped.
        val statements = file.readText().split(';').map { statement ->
            statement.lines().filterNot { it.startsWith("--") || it.isBlank() }.joinToString("\n")
        }.filter { it.isNotEmpty() }
        val schema = ClickHouseSinkSchema(
            AggregateType("transfer", "account"),
            "default",
            AggregateSinkOptions(history = false),
            emptyList(),
            accessColumns = AccessColumns.of(emptyList()),
        )
        statements.first().assert().isEqualTo(schema.ddl.single())
    }

    private companion object {
        const val PROPERTY = "wow-test.clickhouse.scale-check-sql"
    }
}
