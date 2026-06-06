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

internal class CommandRouteMetadataParserTest {

    @Test
    fun `should parse command route metadata with path and header variables`() {
        val commandRouteMetadata = commandRouteMetadata<TestCommandRouteNotRequired>()
        commandRouteMetadata.enabled.assert().isTrue()
        commandRouteMetadata.action.assert().isEqualTo("{id}/{name}")
        commandRouteMetadata.prefix.assert().isEqualTo("")

        val idPathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "id" }
        idPathVariable.field.assert().isEqualTo(TestCommandRouteNotRequired::id.javaField)
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
    fun `should parse delete aggregate route method`() {
        val commandRouteMetadata = commandRouteMetadata<DefaultDeleteAggregate>()
        commandRouteMetadata.method.assert().isEqualTo(Https.Method.DELETE)
    }

    @Test
    fun `should decode command from path and header variables`() {
        val commandRouteMetadata = commandRouteMetadata<TestCommandRoute>()
        val command = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id", "name" to "name")[it] },
            { mapOf("header" to "header-value")[it] }
        )
        command.id.assert().isEqualTo("id")
        command.name.assert().isEqualTo("name")
        command.header.assert().isEqualTo("header-value")
        assertThrownBy<IllegalArgumentException> {
            commandRouteMetadata.decode(
                JsonSerializer.createObjectNode(),
                { mapOf("id" to "id")[it] },
                { null }
            )
        }
    }

    @Test
    fun `should decode command with optional variables using defaults`() {
        val commandRouteMetadata = commandRouteMetadata<TestCommandRouteNotRequired>()
        val commandWithDefault = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id")[it] },
            { null }
        )
        commandWithDefault.id.assert().isEqualTo("id")
        commandWithDefault.name.assert().isEqualTo("otherName")
        commandWithDefault.header.assert().isEqualTo("header")
    }

    @Test
    fun `should decode command with nested path variables`() {
        val commandRouteMetadata = commandRouteMetadata<TestNestedCommandRoute>()
        commandRouteMetadata.action.assert().isEqualTo("{customerId}/{id}/{name}")
        val customerIdPathVariable =
            commandRouteMetadata.pathVariableMetadata.first { it.variableName == "customerId" }
        customerIdPathVariable.fieldName.assert().isEqualTo("id")
        customerIdPathVariable.fieldPath.assert().contains("customer", "id")
        customerIdPathVariable.variableType.assert().isEqualTo(String::class.java)

        val command = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id", "customerId" to "customerId", "name" to "name")[it] },
            { null }
        )
        command.id.assert().isEqualTo("id")
        command.customer.id.assert().isEqualTo("customerId")
        command.customer.name.assert().isEqualTo("name")
    }

    @Test
    fun `should decode command with field-level nested path variables`() {
        TestNestedFieldCommandRoute::customer.toIntimateAnnotationElement()
        val commandRouteMetadata = commandRouteMetadata<TestNestedFieldCommandRoute>()
        commandRouteMetadata.action.assert().isEqualTo("{customerId}/{id}/{name}")
        val command = commandRouteMetadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id", "customerId" to "customerId", "name" to "name")[it] },
            { null }
        )
        command.id.assert().isEqualTo("id")
        command.customer.id.assert().isEqualTo("customerId")
        command.customer.name.assert().isEqualTo("name")
    }

    @Test
    fun `should handle missed variable in route metadata`() {
        val commandRouteMetadata = commandRouteMetadata<TestCommandRouteMissedVariable>()
        commandRouteMetadata.pathVariableMetadata.map { it.variableName }
            .assert().contains("id", "name")
        val namePathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "name" }
        namePathVariable.field.assert().isNull()
        namePathVariable.fieldPath.assert().contains("name")
        namePathVariable.variableType.assert().isNull()
    }

}

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
private data class TestCommandRouteNotRequired(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name", required = false)
    val name: String = "otherName",
    @CommandRoute.HeaderVariable(name = "header", required = false)
    val header: String = "header",
)

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
private data class TestCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name")
    val name: String = "otherName",
    @CommandRoute.HeaderVariable(name = "header")
    val header: String = "header",
)

@CommandRoute("{customerId}/{id}/{name}")
private data class TestNestedCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: TestCustomer
) : DeleteAggregate

private data class TestCustomer(val id: String, val name: String)

@CommandRoute("{customerId}/{id}/{name}")
private data class TestNestedFieldCommandRoute(
    @field:CommandRoute.PathVariable
    val id: String,
    @field:CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @field:CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: TestCustomer
)

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
private data class TestCommandRouteMissedVariable(
    @CommandRoute.PathVariable
    val id: String,
    val name: String = "otherName",
)
