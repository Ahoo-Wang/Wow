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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class StatementLabelTest {
    @Test
    fun `a statement is counted by its verb and first table`() {
        statementLabel("SELECT version FROM \"wow_snapshot__a\" WHERE id = ?").assert()
            .isEqualTo("statement select wow_snapshot__a")
        statementLabel("  insert into wow_log__a (id) values (?)").assert().isEqualTo("statement insert wow_log__a")
        statementLabel("update wow_lease set owner = ?").assert().isEqualTo("statement update wow_lease")
    }

    @Test
    fun `a statement without its text or a table keeps the bare key`() {
        statementLabel(null).assert().isEqualTo("statement")
        statementLabel("commit").assert().isEqualTo("statement commit")
    }
}
