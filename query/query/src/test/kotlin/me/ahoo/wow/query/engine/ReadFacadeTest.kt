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

package me.ahoo.wow.query.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryAudit
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.state.InvalidStateReadException
import me.ahoo.wow.query.state.StateNotFoundException
import me.ahoo.wow.query.state.StateRead
import me.ahoo.wow.spi.observation.Observation
import org.junit.jupiter.api.Test

/** [ReadFacade.observed]: a state read observed and audited as a query is (review R4-Q, Q-7). */
class ReadFacadeTest {
    private val cart = AggregateType("shop", "cart")
    private val ref = cart.ref("acme", "c-1")
    private val caller = QueryCaller(Caller(tenantId = "acme", scope = Scope("alice", null)), QueryEntry.HTTP)

    /** What an observer saw, in order. */
    private class Recording(override val audits: Boolean = true) : QueryObserver {
        val seen = mutableListOf<String>()
        val audited = mutableListOf<QueryAudit>()

        override fun start(aggregateType: AggregateType, queryType: QueryType): Observation {
            seen += "start:$queryType"
            return object : Observation {
                override fun failed(error: Throwable) {
                    seen += "failed:${error.javaClass.simpleName}"
                }

                override fun end() {
                    seen += "end"
                }
            }
        }

        override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
            seen += "complete:$queryType"
        }

        override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) {
            seen += "error:$queryType"
        }

        override fun onCancel(aggregateType: AggregateType, queryType: QueryType) {
            seen += "cancel:$queryType"
        }

        override fun onAudit(audit: QueryAudit) {
            audited += audit
        }
    }

    @Test
    fun `a read that returns is complete, with its rows`(): Unit = runBlocking {
        val observer = Recording()
        val facade = ReadFacade({ null }, observer = observer)
        facade.observed(cart, StateRead.TRACE, caller, rows = { it.size.toLong() }) { listOf(1, 2, 3) }
            .assert().hasSize(3)
        observer.seen.assert().containsExactly("start:TRACE", "complete:TRACE", "end")
        val audit = observer.audited.single()
        audit.aggregateType.assert().isEqualTo(cart)
        audit.queryType.assert().isEqualTo(QueryType.TRACE)
        audit.entry.assert().isEqualTo(QueryEntry.HTTP)
        audit.rows.assert().isEqualTo(3L)
        audit.outcome.assert().isEqualTo(QueryAudit.Outcome.COMPLETE)
        audit.errorCode.assert().isNull()
        audit.model.assert().isNull()
        audit.maskedFields.assert().isEmpty()
        audit.toString().assert().contains("queryType=TRACE")
    }

    @Test
    fun `a read answered as not found is an error whose reason only the audit keeps`(): Unit = runBlocking {
        val observer = Recording()
        val facade = ReadFacade({ null }, observer = observer)
        StateNotFoundException.Reason.entries.forEach { reason ->
            val refused = runCatching {
                facade.observed<Unit>(cart, StateRead.VERSION, caller) { throw StateNotFoundException(ref, reason) }
            }.exceptionOrNull() as StateNotFoundException
            refused.ref.assert().isEqualTo(ref)
            refused.errorCode.assert().isEqualTo(FrameworkErrors.AGGREGATE_NOT_FOUND)
            refused.message.assert().isEqualTo("No state of $ref.")
        }
        observer.audited.map { it.errorCode }.assert().containsExactly(
            "wow.aggregate.not-found:MISSING",
            "wow.aggregate.not-found:OUT_OF_SCOPE",
            "wow.aggregate.not-found:HIDDEN",
        )
        observer.audited.map { it.outcome }.toSet().assert().containsExactly(QueryAudit.Outcome.ERROR)
        observer.seen.take(4).assert()
            .containsExactly("start:STATE", "error:STATE", "failed:StateNotFoundException", "end")
    }

    @Test
    fun `a parameter error is an invalid query with its violation`(): Unit = runBlocking {
        val observer = Recording()
        val invalid = InvalidStateReadException("'version' is a whole number from 1: '0'.", "INVALID_VALUE", "version")
        invalid.errorCode.assert().isEqualTo(QueryErrors.INVALID)
        invalid.errorMsg.assert().isEqualTo(invalid.message)
        invalid.code.assert().isEqualTo(QueryErrorCodes.INVALID_VALUE)
        invalid.name.assert().isEqualTo("version")
        invalid.bindingErrors.single().name.assert().isEqualTo("version")
        val facade = ReadFacade({ null }, observer = observer)
        runCatching { facade.observed<Unit>(cart, StateRead.AT, caller) { throw invalid } }
        observer.audited.single().errorCode.assert().isEqualTo("wow.query.invalid:INVALID_VALUE")
    }

    @Test
    fun `a cancelled read is a cancellation`(): Unit = runBlocking {
        val observer = Recording()
        runCatching {
            ReadFacade({ null }, observer = observer).observed<Unit>(cart, StateRead.LATEST, caller) {
                throw CancellationException("gone")
            }
        }.exceptionOrNull().assert().isInstanceOf(CancellationException::class.java)
        observer.seen.assert().contains("cancel:STATE")
        observer.audited.single().outcome.assert().isEqualTo(QueryAudit.Outcome.CANCEL)
    }

    @Test
    fun `an observer that does not audit builds no audit`(): Unit = runBlocking {
        val observer = Recording(audits = false)
        ReadFacade({ null }, observer = observer).observed(cart, StateRead.LATEST, caller) { "state" }
            .assert().isEqualTo("state")
        observer.seen.assert().containsExactly("start:STATE", "complete:STATE", "end")
        observer.audited.assert().isEmpty()
        // Without an observer, a read only runs.
        ReadFacade.NONE.observed(cart, StateRead.LATEST, caller) { 1 }.assert().isEqualTo(1)
    }

    @Test
    fun `each read is told as a state or a trace, and its shape names only what it asks for`(): Unit = runBlocking {
        StateRead.entries.associateWith { it.queryType }.assert().isEqualTo(
            mapOf(
                StateRead.LATEST to QueryType.STATE,
                StateRead.VERSION to QueryType.STATE,
                StateRead.AT to QueryType.STATE,
                StateRead.TRACE to QueryType.TRACE,
            )
        )
        val observer = Recording()
        val facade = ReadFacade({ null }, observer = observer)
        StateRead.entries.forEach { facade.observed(cart, it, caller) { Unit } }
        facade.observed(cart, StateRead.VERSION, QueryCaller.IN_PROCESS) { Unit }
        val prints = observer.audited.map { it.fingerprint }
        prints.distinct().assert().hasSize(4)
        // Whoever reads, the same shape.
        prints[4].assert().isEqualTo(prints[1])
    }

    @Test
    fun `the engine's reads are observed by the query log and the application's observers`(): Unit = runBlocking {
        val observer = Recording()
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(AggregateManifest("cart", state = StateManifest(Manifest.schemaRef("cart"))))
                ),
            ),
        )
        val engine = QueryEngine.Builder(manifest).observers(listOf(observer)).build()
        engine.reads.observed(cart, StateRead.LATEST, caller) { Unit }
        observer.audited.single().queryType.assert().isEqualTo(QueryType.STATE)
    }
}
