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

package me.ahoo.wow.modeling.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.accessor.property.StaticPropertyGetter
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.command.MockCommandAggregate
import org.junit.jupiter.api.Test

class NamedAggregateGetterTest {

    @Test
    fun `self getter materializes the target named aggregate`() {
        val target = MaterializedNamedAggregate("context", "aggregate")

        SelfNamedAggregateGetter.getNamedAggregate(target).assert().isSameAs(target)
    }

    @Test
    fun `metadata getter always returns configured named aggregate`() {
        val namedAggregate = MaterializedNamedAggregate("context", "aggregate")
        val getter = MetadataNamedAggregateGetter<Any>(namedAggregate)

        getter.getNamedAggregate(Any()).assert().isSameAs(namedAggregate)
    }

    @Test
    fun `simple getter builds named aggregate from target property`() {
        val getter = SimpleNamedAggregateGetter("context", StaticPropertyGetter<Any, String>("aggregate"))
        val namedAggregate = getter.getNamedAggregate(Any())

        namedAggregate.contextName.assert().isEqualTo("context")
        namedAggregate.aggregateName.assert().isEqualTo("aggregate")
    }

    @Test
    fun `property getter extension prefers explicit property getter`() {
        val getter = StaticPropertyGetter<Any, String>("aggregate")
            .toNamedAggregateGetter(Any::class.java)

        getter.assert().isInstanceOf(SimpleNamedAggregateGetter::class.java)
    }

    @Test
    fun `property getter extension falls back to type metadata when property getter is absent`() {
        val getter = null.toNamedAggregateGetter(MockCommandAggregate::class.java)

        getter.assert().isInstanceOf(MetadataNamedAggregateGetter::class.java)
        getter!!.getNamedAggregate(MockCommandAggregate("aggregate-1")).aggregateName
            .assert().isEqualTo("modeling_command_aggregate")
    }
}
