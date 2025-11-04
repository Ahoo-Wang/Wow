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

package me.ahoo.wow.schema.naming

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.impl.DefinitionKey
import com.github.victools.jsonschema.generator.naming.SchemaDefinitionNamingStrategy
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.configuration.namedAggregate
import me.ahoo.wow.configuration.namedBoundedContext
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.modeling.toStringWithAlias

class WowSchemaNamingStrategy(override val defaultSchemaNamePrefix: String) :
    DefaultSchemaNamePrefixCapable,
    SchemaDefinitionNamingStrategy {
    companion object {
        private const val ARRAY_SCHEMA_NAME = "Array"
        fun Class<*>.resolveNamePrefix(): String? {
            this.namedAggregate()?.let {
                return "${it.toStringWithAlias()}."
            }
            namedBoundedContext()?.let {
                return it.getContextAliasPrefix()
            }
            return null
        }

        fun Class<*>.toSchemaName(): String {
            kotlin.scanAnnotation<Schema>()?.let {
                if (it.name.isNotBlank()) {
                    return it.name
                }
            }
            if (this.isArray) {
                return ARRAY_SCHEMA_NAME
            }
            if (!isMemberClass) {
                return simpleName
            }
            return "${enclosingClass.toSchemaName()}.$simpleName"
        }

        fun ResolvedType.flattenType(result: MutableList<Class<*>> = mutableListOf()): List<Class<*>> {
            if (this.isArray) {
                this.arrayElementType?.flattenType(result)
                result.add(Array::class.java)
                return result
            }

            if (!this.typeBindings.isEmpty) {
                this.typeParameters.forEach { it.flattenType(result) }
            }
            result.add(this.erasedType)
            return result
        }

        /**
         * `me.ahoo.wow.api.query.PagedList<me.ahoo.wow.api.query.MaterializedSnapshot<me.ahoo.wow.example.domain.order.OrderState>>`
         *  >> `order.order.OrderStateMaterializedSnapshotPagedList`
         */
        fun ResolvedType.toSchemaName(defaultSchemaNamePrefix: String = ""): String {
            val flatTypes = flattenType()
            val namePrefix = flatTypes.firstNotNullOfOrNull {
                it.resolveNamePrefix()
            } ?: defaultSchemaNamePrefix
            return buildString {
                if (namePrefix.isNotBlank()) {
                    append(namePrefix)
                }
                flatTypes.forEach {
                    append(it.toSchemaName())
                }
            }
        }
    }

    override fun getDefinitionNameForKey(key: DefinitionKey, generationContext: SchemaGenerationContext): String {
        return key.type.toSchemaName(defaultSchemaNamePrefix)
    }

    override fun adjustNullableName(
        key: DefinitionKey?,
        definitionName: String?,
        generationContext: SchemaGenerationContext?
    ): String {
        return definitionName + "Nullable"
    }
}
