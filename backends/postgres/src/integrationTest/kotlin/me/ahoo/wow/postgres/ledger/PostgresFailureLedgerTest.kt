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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.tck.ledger.FailureLedgerSpec
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
    fun `a table created before the retry state reads its entries as never retried, and amends them`(): Unit =
        runBlocking {
            PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS ledger_before_retries")
                    statement.execute(
                        """
                        CREATE TABLE ledger_before_retries (
                            subscription text COLLATE "C" NOT NULL,
                            context text COLLATE "C" NOT NULL,
                            aggregate_name text COLLATE "C" NOT NULL,
                            tenant_id text COLLATE "C" NOT NULL,
                            aggregate_id text COLLATE "C" NOT NULL,
                            from_version integer NOT NULL,
                            reason text NOT NULL,
                            failed_at bigint NOT NULL,
                            PRIMARY KEY (subscription, context, aggregate_name, tenant_id, aggregate_id)
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO ledger_before_retries VALUES ('old', 'ledger', 'order', 't', 'a', 3, 'boom', 7)",
                    )
                }
            }
            val ledger = PostgresFailureLedger(PostgresFixture.dataSource, "ledger_before_retries")
            ledger.createSchema()
            val ref = AggregateType("ledger", "order").ref("t", "a")
            ledger.parked("old", ref).assert().isEqualTo(ParkedAggregate("old", ref, 3, "boom", 7))
            ledger.amend("old", ref, ParkedAmendment.ClaimRetry(0, 9))!!.retries.assert().isEqualTo(1)
            ledger.parked("old", ref)!!.retryAt.assert().isEqualTo(9L)
        }

    @Test
    fun `a table name that is not a plain identifier is refused`() {
        assertThrownBy<IllegalArgumentException> { PostgresFailureLedger(PostgresFixture.dataSource, "x\"; drop") }
    }
}
