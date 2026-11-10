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

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach

/**
 * A [TestCluster] per test, its nodes killed and closed after it, and the owner of an aggregate among its running
 * nodes: what the cluster tests over three nodes share.
 */
abstract class ClusterTestCase {
    protected var cluster = TestCluster()

    @AfterEach
    fun close(): Unit = runBlocking {
        cluster.nodes.values.forEach { it.kill() }
        cluster.close()
    }

    protected fun ownerOf(id: String): String = Hrw.owner(cluster.partitionOf(id), cluster.running.map { it.name })!!
}
