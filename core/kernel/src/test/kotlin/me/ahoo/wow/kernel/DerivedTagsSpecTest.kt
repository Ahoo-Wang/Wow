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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The test DSL's `expectTags` (decision D220), over [shipmentModel], whose state derives its country and labels. */
class DerivedTagsSpecTest {
    @Test
    fun `expectTags checks the tags after a commit, derived keys overriding the applied ones`() {
        aggregateSpec(shipmentModel) {
            whenCommand(Ship("CN")) { expectTags(mapOf("country" to listOf("CN"))) }
            whenCommand(ApplyTags(mapOf("country" to listOf("XX"), "region" to listOf("eu")))) {
                expectTags(mapOf("country" to listOf("CN"), "region" to listOf("eu")))
            }
            whenCommand(Move("US")) { expectTags(mapOf("country" to listOf("US"), "region" to listOf("eu"))) }
        }
    }

    @Test
    fun `expectTags after a command that committed nothing checks the tags the aggregate has`() {
        aggregateSpec(shipmentModel) {
            whenCommand(Ship("CN")) { expectNoError() }
            whenCommand(ApplyTags(mapOf("country" to listOf("XX")))) {
                expectUnchanged()
                expectTags(mapOf("country" to listOf("CN")))
            }
        }
    }

    @Test
    fun `a given state derives its tags`() {
        aggregateSpec(shipmentModel) {
            givenState(version = 3) { country = "JP" }
            whenCommand(ApplyTags(emptyMap())) {
                expectUnchanged()
                expectTags(mapOf("country" to listOf("JP")))
            }
            whenCommand(Label("cold")) { expectTags(mapOf("country" to listOf("JP"), "label" to listOf("cold"))) }
        }
    }

    @Test
    fun `expectTags fails on other tags`() {
        assertThrownBy<AssertionError> {
            aggregateSpec(shipmentModel) {
                whenCommand(Ship("CN")) { expectTags(mapOf("country" to listOf("US"))) }
            }
        }.hasMessage("Expected tags {country=[US]} but got {country=[CN]}.")
    }
}
