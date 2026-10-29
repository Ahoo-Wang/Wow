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
    fun `a ledger table created by a build without format records is refused`() {
        // Finding A2-13: the old layout was accepted silently and failed on its first statement.
        PostgresFixture.createUnrecorded("ledger_unrecorded")
        assertThrownBy<IllegalStateException> {
            PostgresFailureLedger(PostgresFixture.dataSource, "ledger_unrecorded").createSchema()
        }
        PostgresFailureLedger(PostgresFixture.dataSource, "ledger_bumped").createSchema()
        PostgresFixture.bumpFormat("ledger_bumped")
        assertThrownBy<IllegalStateException> {
            PostgresFailureLedger(PostgresFixture.dataSource, "ledger_bumped").createSchema()
        }
    }

    @Test
    fun `services sharing a database see and resolve their own entries only`(): Unit = runBlocking {
        // Finding A2-13: the key had no service, so one service resolved another's parked entry.
        val orders = PostgresFailureLedger(PostgresFixture.dataSource, "ledger_shared", service = "orders")
        val billing = PostgresFailureLedger(PostgresFixture.dataSource, "ledger_shared", service = "billing")
        orders.createSchema()
        billing.createSchema()
        val ref = AggregateType("shared", "order").ref("t", "a")
        orders.park(ParkedAggregate("projection", ref, 0, 3, "boom", 7)).assert().isTrue()
        billing.parked("projection", ref).assert().isNull()
        billing.list("projection").assert().isEmpty()
        billing.resolve("projection", ref).assert().isNull()
        billing.park(ParkedAggregate("projection", ref, 0, 5, "other", 8)).assert().isTrue()
        orders.parked("projection", ref)!!.fromVersion.assert().isEqualTo(3)
        orders.list("projection").map { it.reason }.assert().containsExactly("boom")
        orders.list("projection", after = AggregateType("shared", "a").ref("t", "a")).size.assert().isEqualTo(1)
        orders.resolve("projection", ref)!!.reason.assert().isEqualTo("boom")
        billing.parked("projection", ref)!!.reason.assert().isEqualTo("other")
    }

    @Test
    fun `a table name that is not a plain identifier is refused`() {
        assertThrownBy<IllegalArgumentException> { PostgresFailureLedger(PostgresFixture.dataSource, "x\"; drop") }
    }
}
