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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Review X-10: the heartbeat and reconcile settings that would make a node drop out of the members are refused. */
class ClusterOptionsTest {
    @Test
    fun `the heartbeat interval should be less than half the member TTL`() {
        assertThrows<IllegalArgumentException> { ClusterOptions(memberTtl = 10.seconds, heartbeatInterval = 9.seconds) }
            .message.assert().isEqualTo(
                "The heartbeat interval 9s must be less than half the member TTL 10s: a node whose heartbeat is late " +
                    "once would otherwise drop out of the members and lose its partitions."
            )
        assertThrows<IllegalArgumentException> { ClusterOptions(memberTtl = 10.seconds, heartbeatInterval = 5.seconds) }
        ClusterOptions(memberTtl = 10.seconds, heartbeatInterval = 4_999.milliseconds).heartbeatInterval
            .assert().isEqualTo(4_999.milliseconds)
    }

    @Test
    fun `the reconcile timeout should be longer than the drain timeout`() {
        assertThrows<IllegalArgumentException> {
            ClusterOptions(drainTimeout = 30.seconds, reconcileTimeout = 30.seconds)
        }.message.assert().isEqualTo(
            "The reconcile timeout 30s must be longer than the drain timeout 30s: a hand-off waits up to the drain " +
                "timeout, and a node reconciling longer takes itself as stuck and leaves the members."
        )
        ClusterOptions(drainTimeout = 30.seconds, reconcileTimeout = 31.seconds).reconcileTimeout
            .assert().isEqualTo(31.seconds)
    }

    @Test
    fun `the defaults and the fast test options should hold`() {
        ClusterOptions().heartbeatInterval.assert().isEqualTo(3.seconds)
        fastOptions().memberTtl.assert().isEqualTo(1_500.milliseconds)
    }
}
