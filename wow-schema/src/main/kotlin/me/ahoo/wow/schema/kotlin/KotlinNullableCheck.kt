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

import com.github.victools.jsonschema.generator.ConfigFunction
import com.github.victools.jsonschema.generator.FieldScope
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.schema.Types.isKotlinElement
import kotlin.reflect.jvm.kotlinProperty

object KotlinNullableCheck : ConfigFunction<FieldScope, Boolean> {

    override fun apply(fieldScope: FieldScope): Boolean {
        if (!fieldScope.declaringType.erasedType.isKotlinElement()) {
            return false
        }

        val property = fieldScope.rawMember.kotlinProperty ?: return false
        if (property.returnType.isMarkedNullable) {
            return true
        }
        val schemaAnnotation = property.scanAnnotation<Schema>() ?: return false
        return schemaAnnotation.nullable
    }
}
