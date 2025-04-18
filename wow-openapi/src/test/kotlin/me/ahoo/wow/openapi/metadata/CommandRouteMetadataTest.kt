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
import org.junit.jupiter.api.Test

class CommandRouteMetadataTest {

    @Test
    fun equalTo() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        commandRouteMetadata.assert().isEqualTo(commandRouteMetadata)
    }

    @Test
    fun equalToAny() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        commandRouteMetadata.assert().isNotEqualTo(Any())
    }

    @Test
    fun equalToOther() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        val nestedMockCommandRoute = commandRouteMetadata<NestedMockCommandRoute>()
        commandRouteMetadata.assert().isNotEqualTo(nestedMockCommandRoute)
    }

    @Test
    fun testHasCode() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        commandRouteMetadata.hashCode().assert().isEqualTo(commandRouteMetadata.commandMetadata.hashCode())
    }
}
