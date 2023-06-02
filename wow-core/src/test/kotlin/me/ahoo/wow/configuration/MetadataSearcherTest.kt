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

package me.ahoo.wow.configuration

import me.ahoo.wow.naming.asNamedBoundedContext
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class MetadataSearcherTest {
    @Test
    fun getMetadata() {
        val metadata = MetadataSearcher.metadata
        assertThat(metadata, notNullValue())
    }

    @Test
    fun getContextAlias() {
        assertThat(MOCK_AGGREGATE_METADATA.getContextAlias(), equalTo("tck"))
    }

    @Test
    fun getContextAliasIfNofFound() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            "not-found".asNamedBoundedContext().getContextAlias()
        }
    }

    @Test
    fun aliasConflictDetection() {
        WowMetadata(
            mapOf(
                "cart-service" to BoundedContext(alias = "cart"),
                "order-service" to BoundedContext(alias = "order")
            )
        )
    }

    @Test
    fun aliasConflictDetectionIfEmpty() {
        WowMetadata(
            mapOf(
                "cart-service" to BoundedContext(),
                "order-service" to BoundedContext()
            )
        )
    }

    @Test
    fun aliasConflictDetectionIfDuplicate() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            WowMetadata(
                mapOf(
                    "cart-service" to BoundedContext(alias = "order"),
                    "order-service" to BoundedContext(alias = "order")
                )
            )
        }
    }
}
