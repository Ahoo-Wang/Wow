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

package me.ahoo.wow.mongo.ledger

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.FailureLedgerSpec
import me.ahoo.wow.spi.ledger.ParkedAggregate
import org.junit.jupiter.api.Test

class MongoFailureLedgerTest : FailureLedgerSpec() {
    override fun createLedger(): FailureLedger = ledger()

    private fun ledger(collection: String = "wow_failure_ledger") =
        MongoFailureLedger(MongoLogFixture.client, MongoLogFixture.DATABASE, collection).also { it.createSchema() }

    @Test
    fun `creating the schema twice is harmless`() {
        ledger("ledger_twice")
        ledger("ledger_twice")
    }

    /** "￿" sorts before "😀" (U+1F600) by code point but after it by UTF-16 unit, as Java orders strings. */
    @Test
    fun `aggregates are listed by code point, not by UTF-16 unit`(): Unit = runBlocking {
        val ledger = ledger()
        val subscription = "code-point-" + System.nanoTime()
        val type = AggregateType("ledger", "order")
        val emoji = type.ref("tenant", "😀")
        val last = type.ref("tenant", "￿")
        listOf(emoji, last).forEach { ledger.park(ParkedAggregate(subscription, it, 1, "failed", 0)) }
        ledger.list(subscription).map { it.ref }.assert().isEqualTo(listOf(last, emoji))
        ledger.list(subscription, after = last).map { it.ref }.assert().isEqualTo(listOf(emoji))
    }
}
