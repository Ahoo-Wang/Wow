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

package me.ahoo.wow.viewstore.server

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * The view store as it is deployed: outside development, behind the CoSec gateway (the `cosec` profile), without Kafka
 * (decision D322). Every aggregate of it is published by default (D156), which needs no topic transport, so it starts
 * and publishes none of its streams.
 */
class ViewStoreDeployedTest {
    @Test
    fun `the view store should start outside development without a topic transport`() {
        startViewStore(*DEPLOYED, profiles = listOf("cosec")).use { node ->
            node.context.isActive.assert().isTrue()
        }
    }

    private companion object {
        /** Bound to every interface, as a pod is; in memory, so the backend is not what it checks. */
        val DEPLOYED = arrayOf<Pair<String, Any>>(
            "wow.internal.host" to "0.0.0.0",
            "wow.backend" to "memory",
        )
    }
}
