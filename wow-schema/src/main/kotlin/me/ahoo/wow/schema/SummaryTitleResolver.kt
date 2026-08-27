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

import com.github.victools.jsonschema.generator.ConfigFunction
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.TypeScope
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import kotlin.reflect.jvm.kotlinProperty

object SummaryTitleFieldResolver : ConfigFunction<FieldScope, String> {
    override fun apply(fieldScope: FieldScope): String? {
        val property = fieldScope.rawMember.kotlinProperty ?: return null
        return property.scanAnnotation<Summary>()?.value
    }
}

object SummaryTitleTypeResolver : ConfigFunction<TypeScope, String> {
    override fun apply(typeScope: TypeScope): String? {
        return typeScope.type.erasedType.kotlin.scanAnnotation<Summary>()?.value
    }
}
