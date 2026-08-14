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

package me.ahoo.wow.query.mask

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class LegacyMaskerDeprecationTest {
    @Test
    fun `all retained legacy masker types and extensions are deprecated`() {
        val legacyTypes = listOf(
            DataMasker::class.java,
            DynamicDocumentMasker::class.java,
            AggregateDynamicDocumentMasker::class.java,
            StateDynamicDocumentMasker::class.java,
            EventStreamDynamicDocumentMasker::class.java,
            AggregateDataMasker::class.java,
            DefaultAggregateDataMasker::class.java,
            DataMaskerRegistry::class.java,
            AbstractDataMaskerRegistry::class.java,
            StateDataMaskerRegistry::class.java,
            EventStreamMaskerRegistry::class.java,
            DataMasking::class.java
        )

        legacyTypes.forEach { type ->
            type.isAnnotationPresent(kotlin.Deprecated::class.java).assert().isTrue()
        }

        val extensionMethods = listOf(
            Class.forName("me.ahoo.wow.query.mask.AggregateDataMaskerKt"),
            Class.forName("me.ahoo.wow.query.mask.DataMaskingKt")
        ).flatMap { type -> type.declaredMethods.filter { method -> method.name == "mask" || method.name == "tryMask" } }
        extensionMethods.assert().hasSize(4)
        extensionMethods.forEach { method ->
            method.isAnnotationPresent(kotlin.Deprecated::class.java).assert().isTrue()
        }
    }
}
