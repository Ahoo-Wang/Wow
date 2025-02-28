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

import io.swagger.v3.oas.models.media.NumberSchema
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.openapi.SchemaRef.Companion.toRefSchema
import me.ahoo.wow.openapi.converter.TargetTypeModifyConverter
import org.joda.money.CurrencyUnit
import org.joda.money.Money

class MoneyConverter : TargetTypeModifyConverter() {
    companion object {
        private const val AMOUNT_FIELD = "amount"
        private const val CURRENCY_FIELD = "currency"
    }

    override val targetType: Class<*>
        get() = Money::class.java

    override fun modify(resolvedSchema: Schema<*>): Schema<*> {
        return resolvedSchema.apply {
            properties = mapOf(
                AMOUNT_FIELD to NumberSchema().apply {
                    description = AMOUNT_FIELD
                },
                CURRENCY_FIELD to CurrencyUnit::class.java.toRefSchema()
            )
        }
    }
}
