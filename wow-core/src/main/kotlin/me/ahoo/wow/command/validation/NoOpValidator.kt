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

package me.ahoo.wow.command.validation

import javax.validation.ConstraintViolation
import javax.validation.Validator
import javax.validation.executable.ExecutableValidator
import javax.validation.metadata.BeanDescriptor

object NoOpValidator : Validator {
    override fun <T : Any> validate(`object`: T, vararg groups: Class<*>): Set<ConstraintViolation<T>> {
        return emptySet()
    }

    override fun <T : Any> validateProperty(
        `object`: T,
        propertyName: String,
        vararg groups: Class<*>,
    ): Set<ConstraintViolation<T>> {
        return emptySet()
    }

    override fun <T : Any> validateValue(
        beanType: Class<T>,
        propertyName: String,
        value: Any,
        vararg groups: Class<*>,
    ): Set<ConstraintViolation<T>> {
        return emptySet()
    }

    override fun getConstraintsForClass(clazz: Class<*>): BeanDescriptor {
        throw UnsupportedOperationException()
    }

    override fun <T : Any> unwrap(type: Class<T>): T {
        throw UnsupportedOperationException()
    }

    override fun forExecutables(): ExecutableValidator {
        throw UnsupportedOperationException()
    }
}
