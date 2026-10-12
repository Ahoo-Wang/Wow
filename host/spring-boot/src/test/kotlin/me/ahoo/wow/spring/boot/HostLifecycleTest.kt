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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test

/** The host starts and stops the node in the order of decision D108 and reports its readiness around it. */
class HostLifecycleTest {
    @Test
    fun `readiness and the stop steps should follow the start and stop order`() {
        val host = startHost(sources = listOf(HostTestApp::class.java, TimelineConfiguration::class.java))
        val timeline = host.context.getBean(Timeline::class.java)
        timeline.host = host
        val node = host.node
        host.post("/bank/account/open-account", """{"owner":"carol"}""").status.assert().isEqualTo(200)
        timeline.events.assert().containsExactly("readiness ACCEPTING_TRAFFIC")

        host.close()

        val steps = ServiceNode.Step.entries.map { "$it public=false internal=true" }
        timeline.events.assert().containsExactly(
            "readiness ACCEPTING_TRAFFIC",
            "readiness REFUSING_TRAFFIC",
            *steps.toTypedArray(),
        )
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        host.internalPortOpen().assert().isFalse()
        host.publicPortOpen().assert().isFalse()
    }
}
