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

package me.ahoo.wow.tck.container

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchTestFixtureTest {

    @Test
    fun `should expose authentication username`() {
        ElasticsearchTestFixture().username.assert().isEqualTo(WowTestContainers.ELASTIC_USER)
    }

    @Test
    fun `should reuse and close managed resource`() {
        val fixture = ElasticsearchTestFixture()
        val resource = TestResource("client")

        val first = fixture.getOrCreateResource("client") { resource }
        val second = fixture.getOrCreateResource("client") { TestResource("unused") }

        first.assert().isSameAs(resource)
        second.assert().isSameAs(resource)
        fixture.closeManagedResources()
        resource.closed.assert().isTrue()
    }

    @Test
    fun `should close every resource in reverse order when one close fails`() {
        val fixture = ElasticsearchTestFixture()
        val closeOrder = mutableListOf<String>()
        fixture.getOrCreateResource("first") { TestResource("first", closeOrder) }
        fixture.getOrCreateResource("second") { TestResource("second", closeOrder, failOnClose = true) }

        assertThrows<IllegalStateException> {
            fixture.closeManagedResources()
        }

        closeOrder.assert().containsExactly("second", "first")
    }

    private class TestResource(
        private val name: String,
        private val closeOrder: MutableList<String> = mutableListOf(),
        private val failOnClose: Boolean = false,
    ) : AutoCloseable {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            closeOrder.add(name)
            check(!failOnClose) { "close failed: $name" }
        }
    }
}
