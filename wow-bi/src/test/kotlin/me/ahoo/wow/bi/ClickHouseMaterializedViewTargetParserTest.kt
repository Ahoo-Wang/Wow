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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ClickHouseMaterializedViewTargetParserTest {
    @Test
    fun `should extract exact quoted materialized view target`() {
        ClickHouseMaterializedViewTargetParser.parse(
            "CREATE MATERIALIZED VIEW `consumer db`.`to` TO \"target.db\".\"target table\" " +
                "(`value` String) AS (SELECT 'TO ignored' AS value)"
        ).assert().isEqualTo(BiObjectKey("target.db", "target table"))
    }

    @Test
    fun `should fail closed for unsupported or malformed ddl`() {
        ClickHouseMaterializedViewTargetParser.parse("CREATE VIEW db.view AS SELECT 1").assert().isNull()
        ClickHouseMaterializedViewTargetParser.parse(
            "CREATE MATERIALIZED VIEW db.mv TO target AS SELECT 1"
        ).assert().isNull()
        ClickHouseMaterializedViewTargetParser.parse(
            "CREATE MATERIALIZED VIEW db.mv TO db.first TO db.second AS SELECT 1"
        ).assert().isNull()
    }
}
