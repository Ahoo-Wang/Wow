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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import org.junit.jupiter.api.Test
import me.ahoo.wow.api.aggregate.Creation as ModelCreation
import me.ahoo.wow.api.aggregate.Ownership as ModelOwnership

/**
 * The Manifest states the programming model's vocabulary in its own types, since the contract depends on no module
 * of the model (decision D294): each pair names the same values, both ways.
 */
class ProgrammingModelVocabularyTest {
    @Test
    fun `the error table should name exactly the framework codes`() {
        FrameworkErrors.ALL.map { it.code }.toSet().assert().isEqualTo(FrameworkCodes.ALL)
    }

    @Test
    fun `creation and ownership should name the programming model's values`() {
        Creation.entries.map { it.name }.assert().isEqualTo(ModelCreation.entries.map { it.name })
        Ownership.entries.map { it.name }.assert().isEqualTo(ModelOwnership.entries.map { it.name })
    }
}
