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

package me.ahoo.wow.postgres.ledger

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.FailureLedgerSpec
import org.junit.jupiter.api.Test

class PostgresFailureLedgerTest : FailureLedgerSpec() {
    override fun createLedger(): FailureLedger =
        PostgresFailureLedger(PostgresFixture.dataSource).also { it.createSchema() }

    @Test
    fun `creating the schema twice is harmless`() {
        PostgresFailureLedger(PostgresFixture.dataSource, "ledger_twice").createSchema()
        PostgresFailureLedger(PostgresFixture.dataSource, "ledger_twice").createSchema()
    }

    @Test
    fun `a table name that is not a plain identifier is refused`() {
        assertThrownBy<IllegalArgumentException> { PostgresFailureLedger(PostgresFixture.dataSource, "x\"; drop") }
    }
}
