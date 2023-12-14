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

package me.ahoo.wow.api.messaging.processor

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ProcessorInfoDataTest {
    @Test
    fun `materialize - ProcessorInfoData`() {
        val processorInfoData = ProcessorInfoData("contextName", "processorName")
        val materialized = processorInfoData.materialize()
        assertThat(processorInfoData, sameInstance(materialized))
    }

    @Test
    fun `unknown - ProcessorInfoData`() {
        val processorInfoData = ProcessorInfoData.unknown("contextName")
        assertThat(processorInfoData.processorName, equalTo("Unknown"))
    }
}
