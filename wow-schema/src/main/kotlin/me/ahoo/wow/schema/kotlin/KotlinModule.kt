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

package me.ahoo.wow.schema.kotlin

import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import me.ahoo.wow.schema.kotlin.range.CharRangeDefinitionProvider
import me.ahoo.wow.schema.kotlin.range.IntRangeDefinitionProvider
import me.ahoo.wow.schema.kotlin.range.LongRangeDefinitionProvider

class KotlinModule : Module {
    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        val fieldConfigPart = builder.forFields()
        fieldConfigPart.withNullableCheck(KotlinNullableCheck)
        fieldConfigPart.withReadOnlyCheck(KotlinReadOnlyCheck)
        fieldConfigPart.withRequiredCheck(KotlinRequiredCheck)
        fieldConfigPart.withWriteOnlyCheck(KotlinWriteOnlyCheck)
        fieldConfigPart.withIgnoreCheck(KotlinFieldIgnoreCheck)
        val methodConfigPart = builder.forMethods()
        methodConfigPart.withIgnoreCheck(KotlinMethodIgnoreCheck)
        val generalConfigPart = builder.forTypesInGeneral()
        generalConfigPart.withCustomDefinitionProvider(KotlinCustomDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(CharRangeDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(IntRangeDefinitionProvider)
        generalConfigPart.withCustomDefinitionProvider(LongRangeDefinitionProvider)
    }
}
