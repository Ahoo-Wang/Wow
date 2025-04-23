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

import com.fasterxml.jackson.databind.JavaType
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.naming.CurrentBoundedContext
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.schema.naming.WowSchemaNamingStrategy.Companion.resolveNamePrefix
import me.ahoo.wow.schema.naming.WowSchemaNamingStrategy.Companion.toSchemaName

class BoundedContextSchemaNameConverter : ModelConverter {
    companion object {
        fun AnnotatedType.getRawClass(): Class<*>? {
            val schemaType = this.type
            if (schemaType is Class<*>) {
                return schemaType
            }
            if (schemaType is JavaType) {
                return schemaType.rawClass
            }
            return null
        }
    }

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>
    ): Schema<*>? {
        resolveName(type)
        if (chain.hasNext()) {
            return chain.next().resolve(type, context, chain)
        }
        return null
    }

    private fun resolveName(type: AnnotatedType) {
        if (type.name.isNullOrBlank().not()) {
            return
        }
        val rawClass = type.getRawClass() ?: return

        if (rawClass.isStdType()) {
            return
        }
        val namePrefix = rawClass.resolveNamePrefix() ?: CurrentBoundedContext.context.getContextAliasPrefix()
        type.name = namePrefix + rawClass.toSchemaName()
    }
}
