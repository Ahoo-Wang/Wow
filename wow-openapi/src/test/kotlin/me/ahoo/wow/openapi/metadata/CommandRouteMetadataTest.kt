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

package me.ahoo.wow.openapi.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.CommandRoute
import org.junit.jupiter.api.Test

internal class CommandRouteMetadataTest {

    @Test
    fun `should be equal to same command route metadata`() {
        val metadata = commandRouteMetadata<FixtureCommand>()
        metadata.assert().isEqualTo(metadata)
    }

    @Test
    fun `should not be equal to arbitrary object`() {
        val metadata = commandRouteMetadata<FixtureCommand>()
        metadata.assert().isNotEqualTo(Any())
    }

    @Test
    fun `should not be equal to different command route metadata`() {
        val metadata1 = commandRouteMetadata<FixtureCommand>()
        val metadata2 = commandRouteMetadata<OtherFixtureCommand>()
        metadata1.assert().isNotEqualTo(metadata2)
    }

    @Test
    fun `should have hash code matching command metadata`() {
        val metadata = commandRouteMetadata<FixtureCommand>()
        metadata.hashCode().assert().isEqualTo(metadata.commandMetadata.hashCode())
    }
}

@CommandRoute("{id}")
private data class FixtureCommand(@CommandRoute.PathVariable val id: String)

@CommandRoute("{name}")
private data class OtherFixtureCommand(@CommandRoute.PathVariable val name: String)
