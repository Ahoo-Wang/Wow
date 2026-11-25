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
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.subscription.SubscriptionId
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
    fun `a ledger table without a format record is refused`() {
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
        orders.park(ParkedAggregate(PROJECTION, ref, 0, 3, "boom", 7)).assert().isTrue()
        billing.parked(PROJECTION, ref).assert().isNull()
        billing.list(PROJECTION).assert().isEmpty()
        billing.resolveAnywhere(PROJECTION, ref).assert().isNull()
        billing.park(ParkedAggregate(PROJECTION, ref, 0, 5, "other", 8)).assert().isTrue()
        orders.parked(PROJECTION, ref)!!.fromVersion.assert().isEqualTo(3)
        orders.list(PROJECTION).map { it.reason }.assert().containsExactly("boom")
        orders.list(PROJECTION, after = AggregateType("shared", "a").ref("t", "a")).size.assert().isEqualTo(1)
        orders.resolveAnywhere(PROJECTION, ref)!!.reason.assert().isEqualTo("boom")
        billing.parked(PROJECTION, ref)!!.reason.assert().isEqualTo("other")
    }

    @Test
    fun `a ledger table recorded in another format is refused`() {
        PostgresFailureLedger(PostgresFixture.dataSource, "ledger_other_format").createSchema()
        PostgresFixture.bumpFormat("ledger_other_format")
        assertThrownBy<IllegalStateException> {
            PostgresFailureLedger(PostgresFixture.dataSource, "ledger_other_format").createSchema()
        }.message().contains("format 2", "format 1")
    }

    @Test
    fun `a table name that is not a plain identifier is refused`() {
        assertThrownBy<IllegalArgumentException> { PostgresFailureLedger(PostgresFixture.dataSource, "x\"; drop") }
    }

    @Test
    fun `a mark and a request of a later version should survive a generic amendment, warned of once each`(): Unit =
        runBlocking {
            val table = "ledger_later_values"
            val ledger = PostgresFailureLedger(PostgresFixture.dataSource, table).also { it.createSchema() }
            val ref = AggregateType("shared", "order").ref("t", "later")
            ledger.park(ParkedAggregate(PROJECTION, ref, 0, 3, "boom", 7)).assert().isTrue()
            PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("UPDATE $table SET recoverable = 'later-mark', request = 'later-request'")
                }
            }

            val read = ledger.parked(PROJECTION, ref)!!
            read.recoverable.assert().isEqualTo(Recoverable.UNKNOWN)
            read.request.assert().isNull()
            ledger.list(PROJECTION).single().request.assert().isNull()
            PostgresFailureLedger.unknown.seen.assert().contains("recoverable=later-mark", "request=later-request")
            // Each warned of once: a later read finds it warned already.
            PostgresFailureLedger.unknown.warn("request", "later-request").assert().isFalse()

            // The generic path writes the entry back whole, under the revision read: both values are kept.
            ledger.amend(PROJECTION, ref, ParkedAmendment.ApplyRetrySpec(null)).assert().isNotNull()
            PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.executeQuery("SELECT recoverable, request FROM $table").use { rows ->
                        rows.next().assert().isTrue()
                        rows.getString(1).assert().isEqualTo("later-mark")
                        rows.getString(2).assert().isEqualTo("later-request")
                    }
                }
            }
        }

    private companion object {
        val PROJECTION = SubscriptionId("shared", "projection")
    }
}
