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

package me.ahoo.wow.schema.openapi

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.models.tree.Leaf
import java.math.BigDecimal

// Fixtures copied from wow-schema's tests for the OpenAPI schema builder tests.

enum class TestStatus { ACTIVE, INACTIVE }

data class TestAddress(
    val country: String = "",
    val city: String = "",
    val district: String = "",
)

data class TestItem(
    val productId: String,
    val quantity: Int,
    val price: BigDecimal,
)

data class TestState(
    override val id: String,
    val name: String,
    val nullableField: String? = null,
    val status: TestStatus = TestStatus.ACTIVE,
    val address: TestAddress = TestAddress(),
    val items: List<TestItem> = emptyList(),
) : Identifier

data class CreateTestAggregate(
    val name: String,
    val address: TestAddress,
    val items: List<TestItem>,
    @field:CommandRoute.PathVariable
    val pathId: String,
    @field:CommandRoute.HeaderVariable
    val headerToken: String,
)

data class ChangeTestName(
    @field:Summary("newName")
    @field:Description("The new name to set")
    val name: String,
    @field:Schema(nullable = true)
    val optionalReason: String? = null,
)

data class AnnotationFixture(
    @field:Schema(nullable = true)
    @field:Summary("titleField")
    @field:Description("descField")
    val nullableField: String?,
    @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    val readOnlyField: String?,
    @field:Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
    val writeOnlyField: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val requiredField: String?,
    val enumField: TestStatus? = null,
    val enumMap: Map<TestStatus, String>? = null,
) {
    @get:Schema(description = "getterDesc")
    val getterProp: String get() = ""

    @get:JsonIgnore
    val ignoredProp: String get() = ""

    @get:Schema(hidden = true)
    val hiddenProp: String get() = ""
}

data class TreeNodeFixture(
    override val children: List<TreeNodeFixture>,
    override val sortId: Int,
    override val code: String,
    override val name: String,
) : Leaf<TreeNodeFixture> {
    override fun withChildren(children: List<TreeNodeFixture>): TreeNodeFixture {
        return copy(children = children)
    }
}
