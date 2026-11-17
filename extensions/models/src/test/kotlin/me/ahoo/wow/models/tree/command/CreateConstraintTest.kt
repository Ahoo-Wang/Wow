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

package me.ahoo.wow.models.tree.command

import jakarta.validation.Validation
import me.ahoo.test.asserts.assert
import me.ahoo.wow.models.common.NotBlankNameCapable
import org.junit.jupiter.api.Test

/**
 * The name of a tree's create command is constrained by the application alone (issue 4839): [Create] declares no
 * constraint, so a command's own is the only one a Bean Validation provider reports, with the command's message.
 */
class CreateConstraintTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `a command's own constraint should be the only violation of a blank name`() {
        val violations = validator.validate(CreateCategory(name = " ", parentCode = ""))
        violations.map { it.propertyPath.toString() to it.message }.assert()
            .containsExactly("name" to CreateCategory.NAME_REQUIRED)
    }

    @Test
    fun `a command that declares no constraint should have none`() {
        validator.validate(Unconstrained(name = "", parentCode = "")).assert().isEmpty()
    }

    @Test
    fun `a command should opt into the library's constraint with NotBlankNameCapable`() {
        val violations = validator.validate(OptedIn(name = "", parentCode = ""))
        violations.map { it.propertyPath.toString() }.assert().containsExactly("name")
    }

    private data class Unconstrained(override val name: String, override val parentCode: String) :
        Create<CategoryCreated> {
        override fun toEvent(code: String, sortId: Int): CategoryCreated = CategoryCreated(name, code, sortId)
    }

    private data class OptedIn(override val name: String, override val parentCode: String) :
        Create<CategoryCreated>,
        NotBlankNameCapable {
        override fun toEvent(code: String, sortId: Int): CategoryCreated = CategoryCreated(name, code, sortId)
    }
}
