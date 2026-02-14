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

package me.ahoo.wow.openapi.metadata

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.toIntimateAnnotationElement
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaField

class CommandRouteMetadataParserTest {

    @Test
    fun toCommandRouteMetadata() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRouteNotRequired>()
        commandRouteMetadata.enabled.assert().isTrue()
        commandRouteMetadata.action.assert().isEqualTo("{id}/{name}")
        commandRouteMetadata.prefix.assert().isEqualTo("")
        commandRouteMetadata.appendIdPath.assert().isEqualTo(CommandRoute.AppendPath.DEFAULT)
        val idPathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "id" }
        idPathVariable.field.assert().isEqualTo(MockCommandRouteNotRequired::id.javaField)
        idPathVariable.fieldName.assert().isEqualTo("id")
        idPathVariable.variableName.assert().isEqualTo("id")
        idPathVariable.required.assert().isTrue()
        idPathVariable.variableType.assert().isEqualTo(String::class.java)

        val namePathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "name" }
        namePathVariable.fieldName.assert().isEqualTo("customName")
        namePathVariable.variableName.assert().isEqualTo("name")
        namePathVariable.required.assert().isFalse()
        namePathVariable.variableType.assert().isEqualTo(String::class.java)

        val headerVariable = commandRouteMetadata.headerVariableMetadata.first { it.variableName == "header" }
        headerVariable.fieldName.assert().isEqualTo("header")
        headerVariable.variableName.assert().isEqualTo("header")
        headerVariable.required.assert().isFalse()
        headerVariable.variableType.assert().isEqualTo(String::class.java)
    }

    @Test
    fun asDelete() {
        val commandRouteMetadata = commandRouteMetadata<DefaultDeleteAggregate>()
        commandRouteMetadata.method.assert().isEqualTo(Https.Method.DELETE)
    }

    @Test
    fun decode() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        val command = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            {
                mapOf(
                    "id" to "id",
                    "name" to "name",
                )[it]
            },
            {
                mapOf(
                    "header" to "header-value",
                )[it]
            }
        )
        command.id.assert().isEqualTo("id")
        command.name.assert().isEqualTo("name")
        command.header.assert().isEqualTo("header-value")
        assertThrownBy<IllegalArgumentException> {
            commandRouteMetadata.decode(
                JsonSerializer.createObjectNode(),
                {
                    mapOf(
                        "id" to "id",
                    )[it]
                },
                {
                    null
                }
            )
        }
    }

    @Test
    fun decodeNotRequired() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRouteNotRequired>()
        val command = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            {
                mapOf(
                    "id" to "id",
                    "name" to "name",
                )[it]
            },
            {
                mapOf(
                    "header" to "header-value",
                )[it]
            }
        )

        command.id.assert().isEqualTo("id")
        command.name.assert().isEqualTo("name")
        command.header.assert().isEqualTo("header-value")

        val commandWithDefault = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            {
                mapOf(
                    "id" to "id",
                )[it]
            },
            {
                null
            }
        )
        commandWithDefault.id.assert().isEqualTo("id")
        commandWithDefault.name.assert().isEqualTo("otherName")
        commandWithDefault.header.assert().isEqualTo("header")
    }

    @Test
    fun decodeNested() {
        val commandRouteMetadata = commandRouteMetadata<NestedMockCommandRoute>()
        commandRouteMetadata.action.assert().isEqualTo("{customerId}/{id}/{name}")
        val customerIdPathVariable =
            commandRouteMetadata.pathVariableMetadata.first { it.variableName == "customerId" }
        customerIdPathVariable.fieldName.assert().isEqualTo("id")
        customerIdPathVariable.fieldPath.assert().contains("customer", "id")
        customerIdPathVariable.variableType.assert().isEqualTo(String::class.java)

        val customerNamePathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "name" }
        customerNamePathVariable.fieldName.assert().isEqualTo("name")
        customerNamePathVariable.fieldPath.assert().contains("customer", "name")
        customerNamePathVariable.variableType.assert().isEqualTo(String::class.java)

        val command = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            {
                mapOf(
                    "id" to "id",
                    "customerId" to "customerId",
                    "name" to "name",
                )[it]
            },
            {
                null
            }
        )
        command.id.assert().isEqualTo("id")
        command.customer.id.assert().isEqualTo("customerId")
        command.customer.name.assert().isEqualTo("name")
    }

    @Test
    fun decodeFieldNested() {
        NestedFieldMockCommandRoute::customer.toIntimateAnnotationElement()
        val commandRouteMetadata = commandRouteMetadata<NestedFieldMockCommandRoute>()
        commandRouteMetadata.action.assert().isEqualTo("{customerId}/{id}/{name}")
        val customerIdPathVariable =
            commandRouteMetadata.pathVariableMetadata.first { it.variableName == "customerId" }
        customerIdPathVariable.fieldName.assert().isEqualTo("id")
        customerIdPathVariable.fieldPath.assert().contains("customer", "id")
        customerIdPathVariable.variableType.assert().isEqualTo(String::class.java)
        val command = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            {
                mapOf(
                    "id" to "id",
                    "customerId" to "customerId",
                    "name" to "name",
                )[it]
            },
            {
                null
            }
        )
        command.id.assert().isEqualTo("id")
        command.customer.id.assert().isEqualTo("customerId")
        command.customer.name.assert().isEqualTo("name")
    }

    @Test
    fun missedVariable() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRouteMissedVariable>()
        commandRouteMetadata.pathVariableMetadata.map { it.variableName }
            .assert().contains("id", "name")
        val namePathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "name" }
        namePathVariable.field.assert().isNull()
        namePathVariable.fieldPath.assert().contains("name")
        namePathVariable.variableType.assert().isNull()
    }
}

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
data class MockCommandRouteNotRequired(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name", required = false)
    val name: String = "otherName",
    @CommandRoute.HeaderVariable(name = "header", required = false)
    val header: String = "header",
)

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
data class MockCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name")
    val name: String = "otherName",
    @CommandRoute.HeaderVariable(name = "header")
    val header: String = "header",
)

@CommandRoute("{customerId}/{id}/{name}")
data class NestedMockCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: Customer
) : DeleteAggregate {
    data class Customer(val id: String, val name: String)
}

@CommandRoute("{customerId}/{id}/{name}")
data class NestedFieldMockCommandRoute(
    @field:CommandRoute.PathVariable
    val id: String,
    @field:CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @field:CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: Customer
) {
    data class Customer(val id: String, val name: String)
}

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
data class MockCommandRouteMissedVariable(
    @CommandRoute.PathVariable
    val id: String,
    val name: String = "otherName",
)
