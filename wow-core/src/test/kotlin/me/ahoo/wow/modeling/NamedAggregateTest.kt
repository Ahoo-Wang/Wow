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
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

internal class NamedAggregateTest {

    @Test
    fun `hashCode should be equal when same context and aggregateName`() {
        val materialize = MaterializedNamedAggregate(generateGlobalId(), generateGlobalId())
        val materialize2 = MaterializedNamedAggregate(materialize.contextName, materialize.aggregateName)
        materialize.hashCode().assert().isEqualTo(materialize2.hashCode())
    }

    @Test
    fun `materialize should be same instance`() {
        val materialize = MaterializedNamedAggregate(generateGlobalId(), generateGlobalId())
        materialize.materialize().assert().isSameAs(materialize.materialize())
    }

    @Test
    fun `materialize should be same instance with different aggregateName`() {
        val materialize = MaterializedNamedAggregate(generateGlobalId(), generateGlobalId())
        val materialize2 = MaterializedNamedAggregate(materialize.contextName, generateGlobalId())
        materialize2.assert().isNotEqualTo(materialize)
    }

    @Test
    fun `materialize should be same instance with different contextName`() {
        val materialize = MaterializedNamedAggregate(generateGlobalId(), generateGlobalId())
        val materialize2 = MaterializedNamedAggregate(generateGlobalId(), materialize.aggregateName)
        materialize2.assert().isNotEqualTo(materialize)
    }

    @Test
    fun `toNamedAggregate should return correct MaterializedNamedAggregate when string contains delimiter`() {
        // Arrange
        val context = generateGlobalId()
        val aggregateName = generateGlobalId()
        val input = "$context${NAMED_AGGREGATE_DELIMITER}$aggregateName"

        // Act
        val result = input.toNamedAggregate()

        // Assert
        result.contextName.assert().isEqualTo(context)
        result.aggregateName.assert().isEqualTo(aggregateName)
    }

    @Test
    fun `toNamedAggregate should throw exception when string does not contain delimiter and contextName is null`() {
        // Arrange
        val input = "nameValue"

        // Act & Assert
        assertThrownBy<IllegalArgumentException> {
            input.toNamedAggregate()
        }
    }

    @Test
    fun `toNamedAggregate should return correct MaterializedNamedAggregate when string does not contain delimiter and contextName is not null`() {
        // Arrange
        val context = generateGlobalId()
        val aggregateName = generateGlobalId()

        // Act
        val result = aggregateName.toNamedAggregate(context)

        // Assert
        result.contextName.assert().isEqualTo(context)
        result.aggregateName.assert().isEqualTo(aggregateName)
    }

    @Test
    fun `getContextAliasPrefix should return empty string when alias is empty`() {
        // Arrange
        val namedBoundedContext = MaterializedNamedBoundedContext("")

        // Act
        val result = namedBoundedContext.getContextAliasPrefix()

        // Assert
        result.assert().isEmpty()
    }

    @Test
    fun `getContextAliasPrefix should return alias with delimiter when alias is not blank`() {
        // Arrange
        val namedBoundedContext = MaterializedNamedBoundedContext(generateGlobalId())

        // Act
        val result = namedBoundedContext.getContextAliasPrefix()

        // Assert
        result.assert().isEqualTo("${namedBoundedContext.contextName}${NAMED_AGGREGATE_DELIMITER}")
    }

    @Test
    fun toNamedAggregateString() {
        val context = generateGlobalId()
        val aggregateName = generateGlobalId()
        val namedAggregate = MaterializedNamedAggregate(context, aggregateName)
        namedAggregate.toNamedAggregateString().assert()
            .isEqualTo("${context}${NAMED_AGGREGATE_DELIMITER}$aggregateName")
    }

    @Test
    fun toStringWithAlias() {
        val context = generateGlobalId()
        val aggregateName = generateGlobalId()
        val namedAggregate = MaterializedNamedAggregate(context, aggregateName)
        namedAggregate.toNamedAggregateString().assert()
            .isEqualTo("${context}${NAMED_AGGREGATE_DELIMITER}$aggregateName")
    }
}
