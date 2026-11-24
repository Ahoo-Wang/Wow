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

package me.ahoo.wow.runtime.cluster

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A membership's loops end in two ways (#6872): stopped by the membership itself, by the leave of its last group or an
 * abort, which ends no membership; or on their own, by an error or a cancellation a round threw, which ends it, so
 * every group it serves steps out (#6803).
 *
 * The stop cancels the loops one after the other; a loop may complete, and resume its watch, before the stop cancels
 * that watch, as a loaded machine's other threads may run them. An [UnconfinedTestDispatcher] runs them so at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipLeaveTest {
    @Test
    fun `a membership left by its last group removes its record, and is not told its loops ended`() = runTest {
        val store = InMemoryLeaseStore()
        val membership = membership(store, UnconfinedTestDispatcher(testScheduler))
        val ended = listOf(CompletableDeferred<String>(), CompletableDeferred())
        ended.forEach { told -> membership.whenEnded(backgroundScope) { told.complete(it) } }
        membership.join(GROUP.name) {}
        membership.join(PAYMENTS.name) {}
        store.members(GROUP.service).map { it.node }.assert().containsExactly(NODE)

        val errors = errorsOf {
            membership.leave(PAYMENTS.name)
            membership.leave(GROUP.name)
        }

        store.members(GROUP.service).assert().describedAs("the record after the leave").isEmpty()
        ended.filter { it.isCompleted }.map { it.getCompleted() }.assert().describedAs("ends told").isEmpty()
        errors.assert().isEmpty()
    }

    @Test
    fun `an ownership's stop leaves the record of its membership, and does not step out`() = runTest {
        val store = InMemoryLeaseStore()
        val ownership = ownership(store, membership(store, UnconfinedTestDispatcher(testScheduler)))
        ownership.start()
        store.members(GROUP.service).map { it.node }.assert().containsExactly(NODE)

        val errors = errorsOf { ownership.stop() }

        store.members(GROUP.service).assert().describedAs("the record after the stop").isEmpty()
        ownership.isSteppedOut.assert().isFalse()
        errors.assert().isEmpty()
    }

    @Test
    fun `an ownership's step-out leaves the record of its membership, which ends no other way`() = runTest {
        val store = InMemoryLeaseStore()
        val membership = membership(store, UnconfinedTestDispatcher(testScheduler))
        val ended = CompletableDeferred<String>()
        membership.whenEnded(backgroundScope) { ended.complete(it) }
        val ownership = ownership(store, membership)
        ownership.start()
        try {
            val errors = errorsOf { ownership.stepOut(1.minutes, "its fatal error") }

            store.members(GROUP.service).assert().describedAs("the record after the step-out").isEmpty()
            ownership.isSteppedOut.assert().isTrue()
            ended.isCompleted.assert().describedAs("${ended.takeIf { it.isCompleted }?.getCompleted()}").isFalse()
            errors.assert().isEmpty()
        } finally {
            ownership.stop()
        }
    }

    @Test
    fun `a loop that ends on its own, by an error or a cancellation it threw, still ends the membership`() = runTest {
        // What launchWorker completes normally, the loop's own end, not its scope's stop (#6803).
        val thrown = listOf(
            LinkageError("The lease store's driver has no such method."),
            CancellationException("A cancellation the lease store's driver threw."),
        )
        Loop.entries.forEach { loop ->
            thrown.forEach { failure ->
                val store = Breaking(InMemoryLeaseStore())
                val membership = membership(store, StandardTestDispatcher(testScheduler))
                val ended = CompletableDeferred<String>()
                membership.whenEnded(backgroundScope) { ended.complete(it) }
                membership.join(GROUP.name) {}
                try {
                    store.breaks(loop, failure)
                    delay(1.minutes)

                    ended.isCompleted.assert().describedAs("$loop, ${failure.javaClass.simpleName}").isTrue()
                    ended.getCompleted().assert().isEqualTo(loop.why)
                } finally {
                    membership.abort(GROUP.name)
                }
            }
        }
    }

    @Test
    fun `a membership joined again after its last group left ends when its new loops end on their own`() = runTest {
        val store = Breaking(InMemoryLeaseStore())
        val membership = membership(store, StandardTestDispatcher(testScheduler))
        val ended = CompletableDeferred<String>()
        membership.whenEnded(backgroundScope) { ended.complete(it) }
        membership.join(GROUP.name) {}
        membership.leave(GROUP.name)
        // The leave's stop marked the loops it stopped; the new ones it does not concern.
        membership.join(GROUP.name) {}
        try {
            store.breaks(Loop.HEARTBEATS, LinkageError("The lease store's driver has no such method."))
            delay(1.minutes)

            ended.isCompleted.assert().isTrue()
            ended.getCompleted().assert().isEqualTo(Loop.HEARTBEATS.why)
        } finally {
            membership.abort(GROUP.name)
        }
    }

    /** The loops of a membership, and why it ends when one does. */
    enum class Loop(val why: String) {
        HEARTBEATS("its membership heartbeats ended"),
        READS("its reads of the members ended"),
    }

    /** Throws, once [breaks] says so, at each call of [Loop]'s on the store. */
    private class Breaking(private val delegate: LeaseStore) : LeaseStore by delegate {
        @Volatile
        private var broken: Pair<Loop, Throwable>? = null

        fun breaks(loop: Loop, failure: Throwable) {
            broken = loop to failure
        }

        override suspend fun heartbeat(
            service: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member {
            broken?.takeIf { it.first == Loop.HEARTBEATS }?.let { throw it.second }
            return delegate.heartbeat(service, node, address, ttl, groups)
        }

        override suspend fun members(service: String): List<Member> {
            broken?.takeIf { it.first == Loop.READS }?.let { throw it.second }
            return delegate.members(service)
        }
    }

    private fun TestScope.membership(store: LeaseStore, context: CoroutineContext) = ServiceMembership(
        GROUP.service,
        NODE,
        { "$NODE.internal:7070" },
        store,
        context = context,
        timeSource = testScheduler.timeSource,
    )

    private fun TestScope.ownership(store: LeaseStore, membership: ServiceMembership) = PartitionOwnership(
        GROUP,
        NODE,
        { "$NODE.internal:7070" },
        PARTITIONS,
        store,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        membership = membership,
    )

    /** What the membership logs at ERROR while [block] runs. */
    private suspend fun errorsOf(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(ServiceMembership::class.java) as Logger
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logged)
        try {
            block()
        } finally {
            logger.detachAppender(logged)
        }
        return logged.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
    }

    private companion object {
        const val NODE = "node-a"
        const val PARTITIONS = 4
        val GROUP = LeaseGroup("left-service", "left")
        val PAYMENTS = LeaseGroup("left-service", "payments")
    }
}
