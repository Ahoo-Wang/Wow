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

package me.ahoo.wow.schema

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.schema.AggregatedFieldPaths.commandAggregatedFieldPaths
import me.ahoo.wow.schema.TypeFieldPaths.allFieldPaths
import org.junit.jupiter.api.Test

class AggregatedFieldPathsTest {
    @Test
    fun `should list all field paths for condition type`() {
        val paths = Condition::class.allFieldPaths()
        paths.assert().isNotEmpty()
    }

    @Test
    fun `should list all field paths for test state`() {
        val allFieldPaths = TestState::class.allFieldPaths(parentName = "state")
        allFieldPaths.assert().contains("state.address")
            .contains("state.items")
            .contains("state.name")
            .contains("state.status")
            .doesNotContain("state.class")
    }

    @Test
    fun `should list all field paths for polymorphic fixture`() {
        val paths = PolymorphicFixture::class.allFieldPaths()
        paths.assert().isNotEmpty()
    }

    @Test
    fun `should list command aggregated field paths`() {
        val paths = TestAggregate::class.commandAggregatedFieldPaths()
        paths.assert().isNotEmpty()
    }

    class FieldPathDemoState(override val id: String) : Identifier {
        var address: TestAddress = TestAddress()
        var config: PolymorphicFixture = PolymorphicFixture.Default

        @JsonIgnore
        var ignoredAddresses: List<TestAddress> = emptyList()
        var addressArray: Array<TestAddress> = emptyArray()

        @JsonIgnore(false)
        var pagedQuery: PagedQuery = PagedQuery(Condition.all())
        var pagedList: PagedList<FieldPathDemoState> = PagedList.empty()
    }
}
