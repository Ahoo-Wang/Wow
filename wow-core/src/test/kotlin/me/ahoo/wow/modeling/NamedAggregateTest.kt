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

package me.ahoo.wow.modeling

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

class NamedAggregateTest {

    @Test
    fun `materialized named aggregate equality is based on context and aggregate name`() {
        val aggregate = MaterializedNamedAggregate("context", "aggregate")

        aggregate.assert().isEqualTo(MaterializedNamedAggregate("context", "aggregate"))
        aggregate.hashCode().assert().isEqualTo(MaterializedNamedAggregate("context", "aggregate").hashCode())
        aggregate.assert().isNotEqualTo(MaterializedNamedAggregate("context", "other"))
        aggregate.assert().isNotEqualTo(MaterializedNamedAggregate("other", "aggregate"))
    }

    @Test
    fun `materialize returns existing materialized instance and unwraps decorators`() {
        val materialized = MaterializedNamedAggregate("context", "aggregate")
        val decorated = TestNamedAggregateDecorator(materialized)

        materialized.materialize().assert().isSameAs(materialized)
        decorated.materialize().assert().isSameAs(materialized)
    }

    @Test
    fun `string parser accepts qualified aggregate names`() {
        val aggregate = "context.aggregate".toNamedAggregate()

        aggregate.contextName.assert().isEqualTo("context")
        aggregate.aggregateName.assert().isEqualTo("aggregate")
    }

    @Test
    fun `string parser uses supplied context for unqualified aggregate names`() {
        val aggregate = "aggregate".toNamedAggregate("context")

        aggregate.contextName.assert().isEqualTo("context")
        aggregate.aggregateName.assert().isEqualTo("aggregate")
    }

    @Test
    fun `string parser rejects unqualified names without context`() {
        assertThrownBy<IllegalArgumentException> {
            "aggregate".toNamedAggregate()
        }
    }

    @Test
    fun `named aggregate strings use delimiter and context alias prefix`() {
        val namedAggregate = MaterializedNamedAggregate("context", "aggregate")

        namedAggregate.toNamedAggregateString().assert().isEqualTo("context.aggregate")
        namedAggregate.toStringWithAlias().assert().isEqualTo("context.aggregate")
        MaterializedNamedBoundedContext("").getContextAliasPrefix().assert().isEmpty()
        MaterializedNamedBoundedContext("alias").getContextAliasPrefix().assert().isEqualTo("alias.")
    }

    private data class TestNamedAggregateDecorator(
        override val namedAggregate: NamedAggregate
    ) : NamedAggregateDecorator
}
