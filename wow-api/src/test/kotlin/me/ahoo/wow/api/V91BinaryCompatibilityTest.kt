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

package me.ahoo.wow.api

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test

/**
 * A library compiled against 9.1.5 calls these constructors by their 9.1.5 JVM descriptors; each must still link. A
 * Kotlin call that left a default out compiles to the synthetic `$default` constructor, so that one must link too.
 */
class V91BinaryCompatibilityTest {
    private val defaultMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    @Test
    fun `BindingError keeps its 9_1 constructor`() {
        BindingError::class.java.getConstructor(String::class.java, String::class.java)
            .newInstance("name", "msg")
            .assert().isEqualTo(BindingError("name", "msg", null))
    }

    @Test
    fun `Terms keeps its 9_1 constructors`() {
        val type = AggregationGroup.Terms::class.java
        val field = QueryField("state.status")
        type.getConstructor(QueryField::class.java, String::class.java, String::class.java)
            .newInstance(field, "status", "none")
            .assert().isEqualTo(AggregationGroup.Terms(field = field, alias = "status", missingKey = "none"))
        type.getConstructor(
            QueryField::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            defaultMarker,
        ).newInstance(field, "status", null, 0b100, null)
            .assert().isEqualTo(AggregationGroup.Terms(field = field, alias = "status"))
    }

    @Test
    fun `Histogram keeps its 9_1 constructor`() {
        val field = QueryField("state.amount")
        AggregationGroup.Histogram::class.java
            .getConstructor(QueryField::class.java, String::class.java, Double::class.javaPrimitiveType)
            .newInstance(field, "amount", 10.0)
            .assert().isEqualTo(AggregationGroup.Histogram(field = field, alias = "amount", interval = 10.0))
    }
}
