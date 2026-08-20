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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.SearchRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PitSearchAfterExecutorTest {
    @Test
    fun `complete advances and closes with the latest rotated pit id`() {
        val transport = RecordingPitTransport(
            listOf(
                PitSearchPage(listOf(hit("a", 1), hit("b", 2)), pitId = "pit-1"),
                PitSearchPage(emptyList(), pitId = "pit-2"),
            ),
        )
        val executor = PitSearchAfterExecutor(transport, "index", 2) { request -> request }

        StepVerifier.create(executor.execute(maxResults = null).map { it.source })
            .expectNext("a", "b")
            .verifyComplete()

        transport.openCount.get().assert().isOne()
        transport.closeCount.get().assert().isOne()
        transport.requests.size.assert().isEqualTo(2)
        transport.requests[0].pit()!!.id().assert().isEqualTo("pit-id")
        transport.requests[1].pit()!!.id().assert().isEqualTo("pit-1")
        transport.requests[0].searchAfter().assert().isEmpty()
        transport.requests[1].searchAfter().single().longValue().assert().isEqualTo(2L)
        transport.closedPitIds.assert().containsExactly("pit-2")
    }

    @Test
    fun `downstream cancel closes the latest rotated pit id`() {
        val cancel = RecordingPitTransport(
            listOf(PitSearchPage(listOf(hit("a", 1), hit("b", 2)), pitId = "pit-1")),
        )
        StepVerifier.create(PitSearchAfterExecutor(cancel, "index", 2) { it }.execute(null), 1)
            .expectNextCount(1)
            .thenCancel()
            .verify()
        cancel.closeCount.get().assert().isOne()
        cancel.requests.size.assert().isOne()
        cancel.closedPitIds.assert().containsExactly("pit-1")
    }

    @Test
    fun `search error closes the latest rotated pit id`() {
        val failure = RecordingPitTransport(
            pages = listOf(PitSearchPage(listOf(hit("a", 1)), pitId = "pit-1")),
            searchError = IllegalStateException("boom"),
            searchErrorAt = 1,
        )
        StepVerifier.create(PitSearchAfterExecutor(failure, "index", 1) { it }.execute(null))
            .expectNextCount(1)
            .expectError(QueryException::class.java)
            .verify()
        failure.closeCount.get().assert().isOne()
        failure.closedPitIds.assert().containsExactly("pit-1")
    }

    @Test
    fun `decode failure closes the latest rotated pit id`() {
        val decode = RecordingPitTransport(
            listOf(PitSearchPage(listOf(hit("a", 1)), pitId = "pit-1")),
        )
        StepVerifier.create(
            PitSearchAfterExecutor(decode, "index", 2) { it }.execute(null)
                .map<Any> { throw IllegalArgumentException("decode") },
        ).expectError(IllegalArgumentException::class.java).verify()
        decode.closeCount.get().assert().isOne()
        decode.closedPitIds.assert().containsExactly("pit-1")
    }

    @Test
    fun `finite administrator budget reads one sentinel then fails and closes`() {
        val transport = RecordingPitTransport(
            listOf(PitSearchPage(listOf(hit("a", 1), hit("b", 2), hit("c", 3)), pitId = "pit-1")),
        )

        StepVerifier.create(PitSearchAfterExecutor(transport, "index", 3) { it }.execute(maxResults = 2))
            .expectNextCount(2)
            .expectError(QueryException::class.java)
            .verify()

        transport.closeCount.get().assert().isOne()
        transport.closedPitIds.assert().containsExactly("pit-1")
        transport.requests.single().size().assert().isEqualTo(3)
    }

    @Test
    fun `request limit truncates normally while administrator limit uses a sentinel`() {
        val requestLimited = RecordingPitTransport(
            listOf(PitSearchPage(listOf(hit("a", 1), hit("b", 2), hit("c", 3)))),
        )
        StepVerifier.create(
            PitSearchAfterExecutor(requestLimited, "index", 3) { it }
                .execute(requestLimit = 2, maxResults = null),
        ).expectNextCount(2).verifyComplete()
        requestLimited.requests.single().size().assert().isEqualTo(2)

        val administratorLimited = RecordingPitTransport(
            listOf(PitSearchPage(listOf(hit("a", 1), hit("b", 2), hit("c", 3)))),
        )
        StepVerifier.create(
            PitSearchAfterExecutor(administratorLimited, "index", 3) { it }
                .execute(requestLimit = null, maxResults = 2),
        ).expectNextCount(2).expectError(QueryException::class.java).verify()
        administratorLimited.requests.single().size().assert().isEqualTo(3)
    }

    @Test
    fun `missing or duplicate terminal sort fails safely and closes`() {
        listOf(
            PitSearchPage(listOf(PitSearchHit("a", emptyList()))),
            PitSearchPage(listOf(hit("a", 1), hit("b", 1))),
        ).forEach { page ->
            val transport = RecordingPitTransport(listOf(page))
            StepVerifier.create(PitSearchAfterExecutor(transport, "index", 2) { it }.execute(null))
                .expectError(QueryException::class.java)
                .verify()
            transport.closeCount.get().assert().isOne()
        }

        val crossPage = RecordingPitTransport(
            listOf(
                PitSearchPage(listOf(hit("a", 1))),
                PitSearchPage(listOf(hit("a-again", 1))),
            ),
        )
        StepVerifier.create(PitSearchAfterExecutor(crossPage, "index", 1) { it }.execute(null))
            .expectNextCount(1)
            .expectError(QueryException::class.java)
            .verify()
        crossPage.closeCount.get().assert().isOne()
    }

    @Test
    fun `absolute deadline cancels an in-flight search and closes pit exactly once`() {
        val scheduler = VirtualTimeScheduler.create()
        val searchCancelled = AtomicBoolean()
        val transport = RecordingPitTransport(
            listOf(PitSearchPage(listOf(hit("a", 1)), pitId = "pit-1")),
        ).also {
            it.searchPublishers[1] = Mono.never<String>().doOnCancel {
                searchCancelled.set(true)
            }.thenReturn(PitSearchPage(emptyList()))
        }
        val executor = PitSearchAfterExecutor(
            transport,
            "index",
            1,
            now = { Instant.EPOCH },
            deadlineScheduler = scheduler,
        ) { it }

        StepVerifier.withVirtualTime(
            {
                executor.execute(null, Instant.EPOCH.plusSeconds(2))
            },
            { scheduler },
            1,
        ).expectNextCount(1)
            .thenAwait(java.time.Duration.ofSeconds(2))
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert()
                    .isEqualTo(me.ahoo.wow.api.query.error.QueryErrorCode.DEADLINE_EXCEEDED)
            }
            .verify()

        transport.openCount.get().assert().isOne()
        transport.closeCount.get().assert().isOne()
        transport.closedPitIds.assert().containsExactly("pit-1")
        searchCancelled.get().assert().isTrue()
    }

    @Test
    fun `absolute deadline cancels an in-flight pit open without closing an unowned resource`() {
        val scheduler = VirtualTimeScheduler.create()
        val openCancelled = AtomicBoolean()
        val transport = RecordingPitTransport(emptyList()).also {
            it.openPublisher = Mono.never<String>().doOnCancel { openCancelled.set(true) }
        }
        val executor = PitSearchAfterExecutor(
            transport,
            "index",
            1,
            now = { Instant.EPOCH },
            deadlineScheduler = scheduler,
        ) { it }

        StepVerifier.withVirtualTime(
            { executor.execute(null, Instant.EPOCH.plusSeconds(2)) },
            { scheduler },
            0,
        ).thenAwait(java.time.Duration.ofSeconds(2))
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert()
                    .isEqualTo(me.ahoo.wow.api.query.error.QueryErrorCode.DEADLINE_EXCEEDED)
            }
            .verify()

        transport.openCount.get().assert().isOne()
        openCancelled.get().assert().isTrue()
        transport.closeCount.get().assert().isZero()
    }

    @Test
    fun `open empty fails and close error is sanitized`() {
        val emptyOpen = RecordingPitTransport(emptyList()).also { it.openPublisher = Mono.empty() }
        StepVerifier.create(PitSearchAfterExecutor(emptyOpen, "index", 2) { it }.execute(null))
            .expectError(QueryException::class.java)
            .verify()
        emptyOpen.closeCount.get().assert().isZero()

        val closeError = RecordingPitTransport(listOf(PitSearchPage(emptyList()))).also {
            it.closePublisher = Mono.error(IllegalStateException("secret pit id"))
        }
        StepVerifier.create(PitSearchAfterExecutor(closeError, "index", 2) { it }.execute(null))
            .expectErrorSatisfies { error ->
                (error as QueryException).message!!.contains("secret").assert().isFalse()
            }
            .verify()
        closeError.closeCount.get().assert().isOne()
    }

    @Test
    fun `fatal cleanup errors remain fatal`() {
        val fatal = OutOfMemoryError("fatal")
        val transport = RecordingPitTransport(listOf(PitSearchPage(emptyList()))).also {
            it.closePublisher = Mono.error(fatal)
        }

        assertThrows<OutOfMemoryError> {
            PitSearchAfterExecutor(transport, "index", 2) { it }.execute(null).collectList().block()
        }.assert().isSameAs(fatal)
    }

    private fun hit(source: String, sort: Long): PitSearchHit<String> =
        PitSearchHit(source, listOf(FieldValue.of(sort)))
}

private class RecordingPitTransport(
    private val pages: List<PitSearchPage<String>>,
    private val searchError: Throwable? = null,
    private val searchErrorAt: Int = 0,
) : PitSearchAfterTransport<String> {
    val openCount = AtomicInteger()
    val closeCount = AtomicInteger()
    val closedPitIds = mutableListOf<String>()
    val requests = mutableListOf<SearchRequest>()
    var openPublisher: Mono<String>? = null
    val searchPublishers = mutableMapOf<Int, Mono<PitSearchPage<String>>>()
    var closePublisher: Mono<Void>? = null

    override fun open(index: String) = Mono.defer {
        openCount.incrementAndGet()
        openPublisher ?: Mono.just("pit-id")
    }

    override fun search(request: SearchRequest): Mono<PitSearchPage<String>> = Mono.defer {
        requests += request
        val requestIndex = requests.lastIndex
        if (requestIndex == searchErrorAt) {
            searchError?.let { return@defer Mono.error(it) }
        }
        searchPublishers[requestIndex] ?: Mono.just(pages.getOrElse(requestIndex) { PitSearchPage(emptyList()) })
    }

    override fun close(pitId: String) = Mono.defer {
        closeCount.incrementAndGet()
        closedPitIds += pitId
        closePublisher ?: Mono.empty()
    }
}
