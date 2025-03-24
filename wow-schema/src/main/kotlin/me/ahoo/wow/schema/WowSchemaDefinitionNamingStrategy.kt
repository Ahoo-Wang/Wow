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

package me.ahoo.wow.schema

import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.impl.DefinitionKey
import com.github.victools.jsonschema.generator.naming.SchemaDefinitionNamingStrategy
import me.ahoo.wow.api.Wow
import me.ahoo.wow.configuration.namedAggregate
import me.ahoo.wow.configuration.namedBoundedContext
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.naming.getContextAlias

object WowSchemaDefinitionNamingStrategy : SchemaDefinitionNamingStrategy {
    fun Class<*>.toSchemaName(): String {
        kotlin.scanAnnotation<io.swagger.v3.oas.annotations.media.Schema>()?.let {
            if (it.name.isNotBlank()) {
                return it.name
            }
        }
        this.namedAggregate()?.let {
            return "${it.toStringWithAlias()}.$simpleName"
        }
        namedBoundedContext()?.let {
            it.getContextAlias().let { alias ->
                return "$alias.$simpleName"
            }
        }
        if (name.startsWith("me.ahoo.wow.")) {
            return Wow.WOW_PREFIX + simpleName
        }
        return simpleName
    }

    override fun getDefinitionNameForKey(key: DefinitionKey, generationContext: SchemaGenerationContext): String {
        return key.type.erasedType.toSchemaName()
    }
}
