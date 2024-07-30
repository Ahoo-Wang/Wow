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

package me.ahoo.wow.openapi.converter

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.openapi.converter.BoundedContextSchemaNameConverter.Companion.getRawClass

abstract class TargetTypeModifyConverter : ModelConverter {
    abstract val targetType: Class<*>

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>
    ): Schema<*>? {
        if (!chain.hasNext()) {
            return null
        }
        val resolvedSchema = chain.next().resolve(type, context, chain) ?: return null
        if (!isTargetType(type) || resolvedSchema.properties == null) {
            return resolvedSchema
        }
        return modify(resolvedSchema)
    }

    abstract fun modify(resolvedSchema: Schema<*>): Schema<*>

    private fun isTargetType(type: AnnotatedType): Boolean {
        val rawClass = type.getRawClass() ?: return false
        return rawClass == targetType
    }
}
