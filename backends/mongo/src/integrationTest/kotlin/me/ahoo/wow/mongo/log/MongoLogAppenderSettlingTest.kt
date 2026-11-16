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

package me.ahoo.wow.mongo.log

import com.mongodb.MongoException
import com.mongodb.client.MongoClient
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test

/**
 * A sequence allotted to an append is settled on every exit before its insert: a failure between the allotment and
 * the insert (a block's closing, a reservation, a lookup) leaves no sequence this node's tail waits at for ever.
 *
 * Each fault fires once, at the one call it names, and each log has one partition: the tail reads its entries in
 * sequence order, so the exact entries it returns, within the stall grace and a margin ([tailed]), are certain.
 */
class MongoLogAppenderSettlingTest : MongoLogFixtures() {
    private val logContext = MongoLogFixture.newContext()
    private val order = AggregateType(logContext, "order")
    private val cart = AggregateType(logContext, "cart")

    private fun refused(): Nothing = throw MongoException(QUOTA_EXCEEDED, "refused once")

    private fun logOf(client: MongoClient) =
        MongoEventLog(client, MongoLogFixture.DATABASE, logContext, 1).also { it.createSchema() }

    /** A first stream of `order`, then [failing] thrown, then a first stream of `cart`: this log's tail reads both. */
    private fun throwsThenGoesOn(log: EventLog, failing: suspend () -> Unit) = runBlocking {
        log.append(stream("before", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        assertThrownBy<MongoException> { runBlocking { failing() } }
        log.append(stream("after", 1, aggregate = cart)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 2).map { it.stream.ref.id }.assert().containsExactly("before", "after")
    }

    /**
     * A client whose first placeholder of each of [reasons] the server refuses, once; [reserve] runs before each of its
     * reservations.
     */
    private fun refusing(vararg reasons: String, reserve: () -> Unit = {}): MongoClient {
        val left = reasons.toMutableSet()
        return faulty(reserve = reserve) { document, insert ->
            if (document.getString(MongoEventLog.GAP)?.let(left::remove) == true) refused()
            insert()
        }
    }

    /** A group of a first `order` stream, which the open block takes, then a first `cart` one, which takes a new block. */
    private fun orderThenCart() =
        listOf(Append.of(stream("a", 1, aggregate = order)), Append.of(stream("b", 1, aggregate = cart)))

    @Test
    fun `a block's closing the server refuses is left to the tail, and the append after it commits`(): Unit =
        runBlocking {
            val log = logOf(refusing(MongoEventLog.GAP_CLOSED))
            log.append(stream("before", 1, aggregate = order))
            // The first stream of `cart`, a collection the open block was not reserved for, takes a new block.
            log.append(stream("closing", 1, aggregate = cart))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.append(stream("after", 1, aggregate = cart))
            tailed(log, 3).map { it.stream.ref.id }.assert().containsExactly("before", "closing", "after")
        }

    @Test
    fun `a group whose block's closing is refused midway commits, and the tail closes the block`(): Unit =
        runBlocking {
            val log = logOf(refusing(MongoEventLog.GAP_CLOSED))
            log.append(stream("before", 1, aggregate = order))
            log.appendAll(orderThenCart())
                .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            tailed(log, 3).map { it.stream.ref.id }.assert().containsExactly("before", "a", "b")
        }

    /** A log whose next reservation, once armed, fails, and whose placeholders of [reasons] are refused once. */
    private fun failingReservation(vararg reasons: String) {
        var armed = false
        val log = logOf(
            refusing(*reasons) {
                if (armed) {
                    armed = false
                    refused()
                }
            },
        )
        throwsThenGoesOn(log) {
            armed = true
            log.appendAll(orderThenCart())
        }
        armed.assert().isFalse()
    }

    @Test
    fun `a group whose reservation fails midway fills the sequences it allotted`() = failingReservation()

    @Test
    fun `a group whose reservation fails midway leaves a sequence it cannot fill to the tail`() =
        failingReservation(MongoEventLog.GAP_FAILED)

    @Test
    fun `a group whose lookup fails midway fills the sequences it allotted`(): Unit = runBlocking {
        // Another node wrote x's first stream, so this node reads x's latest to append its second.
        MongoLogFixture.log(1, logContext).append(stream("x", 1, aggregate = order))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        var armed = false
        val log = logOf(
            faulty(find = { filter ->
                if (armed && "\"x\"" in filter.toBsonDocument().toJson()) {
                    armed = false
                    refused()
                }
            }),
        )
        armed = true
        assertThrownBy<MongoException> {
            runBlocking {
                log.appendAll(
                    listOf(Append.of(stream("a", 1, aggregate = order)), Append.of(stream("x", 2, aggregate = order))),
                )
            }
        }
        armed.assert().isFalse()
        log.append(stream("after", 1, aggregate = cart)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        // The other node's block is sealed after the grace; a's sequence, in this node's, was filled.
        tailed(log, 2).map { it.stream.ref.id }.assert().containsExactly("x", "after")
    }

    private companion object {
        const val QUOTA_EXCEEDED = 12_501
    }
}
