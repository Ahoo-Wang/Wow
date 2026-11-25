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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Round 2 of the deep review: the lease renewals' watch (#6790) has the shape #6875 fixed in the membership's.
 *
 * An ownership's stop cancels its upkeep's children one after the other: the renewals' loop, then its watch
 * ([LeaseRenewals.launchIn]). The loop may complete, and resume its watch's `loop.join()`, before the stop cancels the
 * watch, as a loaded machine's other threads may run them; an [UnconfinedTestDispatcher] runs them so at once. The watch
 * then takes the stop for the loop's own end: it logs at ERROR that the renewals ended and steps the ownership out,
 * though nothing failed. `MembershipLeaveTest` holds the same stop for the membership's watches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2RenewalsStopTest {
    @Test
    fun `an ownership's stop ends its renewals without taking it for their own end`() = runTest {
        val store = InMemoryLeaseStore()
        val membership = ServiceMembership(
            GROUP.service,
            NODE,
            { "$NODE.internal:7070" },
            store,
            context = StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
        )
        val ownership = PartitionOwnership(
            GROUP,
            NODE,
            { "$NODE.internal:7070" },
            PARTITIONS,
            store,
            context = UnconfinedTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
            membership = membership,
        )
        ownership.start()
        val errors = errorsOf { ownership.stop() }
        errors.assert().describedAs("what the stop logged at ERROR").isEmpty()
        ownership.isSteppedOut.assert().describedAs("whether the stopped ownership stepped out").isFalse()
    }

    /** What the ownership (and its renewals, which log under its name) logs at ERROR while [block] runs. */
    private suspend fun errorsOf(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(PartitionOwnership::class.java) as Logger
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
        val GROUP = LeaseGroup("renewed-service", "renewed")
    }
}
