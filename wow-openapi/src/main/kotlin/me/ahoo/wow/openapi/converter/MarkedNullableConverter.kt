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
import io.swagger.v3.core.converter.ModelConverterContextImpl
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.asPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.openapi.converter.BoundedContextSchemaNameConverter.Companion.getRawClass
import org.slf4j.LoggerFactory

class MarkedNullableConverter : ModelConverter {
    companion object {
        private val log = LoggerFactory.getLogger(MarkedNullableConverter::class.java)
        val GET_PROCESSED_TYPES: PropertyGetter<ModelConverterContextImpl, Set<AnnotatedType>> = try {
            ModelConverterContextImpl::class.java.getDeclaredField("processedTypes")
                .asPropertyGetter()
        } catch (throwable: Throwable) {
            if (log.isWarnEnabled) {
                log.warn("Can not find processedTypes field in ModelConverterContextImpl.", throwable)
            }
            PropertyGetter { emptySet() }
        }

    }

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>
    ): Schema<*>? {
        if (!chain.hasNext()) {
            return null
        }
        val schema = chain.next().resolve(type, context, chain) ?: return null
        val parentSchema = type.parent ?: return schema
        if (context !is ModelConverterContextImpl) {
            return schema
        }
        val processedTypes = GET_PROCESSED_TYPES.get(context)
        val parentType = processedTypes.firstOrNull {
            it.name == parentSchema.name
        } ?: return schema
        val parentClass = parentType.getRawClass() ?: return schema

        val member = parentClass.kotlin.members.firstOrNull {
            it.name == type.propertyName
        } ?: return schema
        if (member.returnType.isMarkedNullable) {
            schema.nullable(true)
        }
        if (member.isFinal) {
            schema.readOnly(true)
        }
        return schema
    }
}