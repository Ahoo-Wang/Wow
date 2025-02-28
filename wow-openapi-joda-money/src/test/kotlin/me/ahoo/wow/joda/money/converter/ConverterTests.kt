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

package me.ahoo.wow.joda.money.converter

import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.joda.money.CurrencyUnit
import org.joda.money.Money
import org.junit.jupiter.api.Test

class ConverterTests {
    @Test
    fun currencyUnit() {
        val schemaRef = CurrencyUnit::class.java.toSchemaRef()
        assertThat(schemaRef.name, equalTo(CurrencyUnit::class.java.simpleName))
        assertThat(schemaRef.ref.`$ref`, equalTo("#/components/schemas/CurrencyUnit"))
        val currencyUnitScheme = schemaRef.component
        assertThat(currencyUnitScheme.type, equalTo("string"))
        assertThat(currencyUnitScheme.format, equalTo("currency"))
        assertThat(currencyUnitScheme.description, equalTo("CurrencyUnit"))
        assertThat(currencyUnitScheme.properties, equalTo(emptyMap()))
    }

    @Test
    fun money() {
        val schemaRef = Money::class.java.toSchemaRef()
        assertThat(schemaRef.name, equalTo(Money::class.java.simpleName))
        assertThat(schemaRef.ref.`$ref`, equalTo("#/components/schemas/Money"))
        val currencyUnitScheme = schemaRef.component
        assertThat(currencyUnitScheme.type, equalTo("object"))
    }
}
