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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.junit.jupiter.api.Test

class LegacyFilterScopeTest {
    @Suppress("DEPRECATION")
    @Test
    fun `should not reapply active deletion scope to converted legacy filter`() {
        val query = SnapshotFilterCompiler.compilePhysical(
            AndFilter(
                listOf(
                    Condition.eq("state.name", "Wow").toFilterExpression(),
                    DeletionFilter(DeletionState.DELETED),
                ),
            ),
        )

        val filters = query.bool().filter()
        filters.assert().hasSize(2)
        filters.single { it.term().field() == StateAggregateRecords.DELETED }.term().value().booleanValue().assert()
            .isTrue()
        filters.single { it.term().field() == "state.name" }.term().value().stringValue().assert().isEqualTo("Wow")
    }
}
