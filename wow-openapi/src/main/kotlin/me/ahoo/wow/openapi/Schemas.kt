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

package me.ahoo.wow.openapi

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.api.Wow
import me.ahoo.wow.configuration.asNamedBoundedContext
import me.ahoo.wow.naming.getContextAlias

object Schemas {
    fun Class<*>.asSchemName(): String {
        asNamedBoundedContext()?.let {
            it.getContextAlias().let { alias ->
                return "$alias.$simpleName"
            }
        }
        if (name.startsWith("me.ahoo.wow.")) {
            return Wow.WOW_PREFIX + simpleName
        }
        return simpleName
    }

    fun Class<*>.asSchemas(): Map<String, Schema<*>> {
        return ModelConverters.getInstance().readAll(this)
    }

    fun Iterable<Class<*>?>.asSchemas(): Map<String, Schema<*>> {
        val schemas = mutableMapOf<String, Schema<*>>()
        forEach {
            if (it != null) {
                schemas.putAll(it.asSchemas())
            }
        }

        return schemas
    }

    fun Class<*>.asSchemaRef(): Schema<*> {
        return this.asSchemName().asSchemaRef()
    }

    fun String.asSchemaRef(): Schema<*> {
        val schemaRef = "${Components.COMPONENTS_SCHEMAS_REF}$this"
        return Schema<Any>().`$ref`(schemaRef)
    }
}
