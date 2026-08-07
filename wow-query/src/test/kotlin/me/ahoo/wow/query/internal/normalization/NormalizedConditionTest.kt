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

package me.ahoo.wow.query.internal.normalization

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class NormalizedConditionTest {

    @Test
    fun `logical path should isolate and expose immutable segments`() {
        val source = mutableListOf("state", "name")
        val path = LogicalField.Path(source, PathBasis.ROOT)

        source.add("late")

        path.segments.assert().containsExactly("state", "name")
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (path.segments as MutableList<String>).add("other")
        }
    }

    @Test
    fun `junction should isolate mutable children`() {
        val source = mutableListOf<NormalizedCondition>(NormalizedCondition.All)
        val junction = NormalizedCondition.Junction(JunctionOperator.AND, source)

        source.add(NormalizedCondition.All)

        junction.children.assert().containsExactly(NormalizedCondition.All)
    }

    @Test
    fun `predicate should enforce value arity`() {
        val field = LogicalField.Path(listOf("state", "status"), PathBasis.ROOT)

        assertThrownBy<IllegalArgumentException> {
            NormalizedCondition.Predicate(field, PredicateOperator.EQ)
        }
        assertThrownBy<IllegalArgumentException> {
            NormalizedCondition.Predicate(field, PredicateOperator.IS_NULL, NormalizedValue.Null)
        }
    }

    @Test
    fun `search scope and text should not be blank`() {
        assertThrownBy<IllegalArgumentException> {
            SearchScopeId(" ")
        }
        assertThrownBy<IllegalArgumentException> {
            NormalizedCondition.Search(SearchScope.Named(SearchScopeId("default")), " ")
        }
    }

    @Test
    fun `native condition should retain immutable backend json`() {
        val condition = NormalizedCondition.Native(
            backendId = BackendId("elasticsearch"),
            payload = Utf8Json("{\"term\":{\"state\":\"PAID\"}}"),
        )

        condition.backendId.value.assert().isEqualTo("elasticsearch")
        condition.payload.value.assert().isEqualTo("{\"term\":{\"state\":\"PAID\"}}")
    }
}
