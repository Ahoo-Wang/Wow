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

import com.github.victools.jsonschema.generator.FieldScope
import java.util.function.Predicate
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.kotlinProperty

object KotlinReadOnlyCheck : Predicate<FieldScope> {

    override fun test(fieldScope: FieldScope): Boolean {
        val property = fieldScope.rawMember.kotlinProperty ?: return false
        if (property.isLateinit) {
            return true
        }
        if (property is KMutableProperty<*>) {
            return false
        }
        /**
         * 判断是否在构造函数中赋值
         */
        return fieldScope.isConstructorParameter().not()
    }

    fun FieldScope.primaryConstructor(): KFunction<*>? {
        return this.rawMember?.declaringClass?.kotlin?.primaryConstructor
    }

    fun FieldScope.isConstructorParameter(): Boolean {
        val primaryConstructor = primaryConstructor() ?: return false
        return primaryConstructor.parameters.any { it.name == this.name }
    }
}
