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

package me.ahoo.wow.infra.accessor.property

import me.ahoo.wow.infra.accessor.field.FieldAccessor
import me.ahoo.wow.infra.accessor.method.SimpleMethodAccessor
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private val LOG = LoggerFactory.getLogger(PropertyDescriptor::class.java)

object PropertyDescriptor {
    private const val PROPERTY_GETTER_PREFIX = "get"
    private const val PROPERTY_SETTER_PREFIX = "set"

    fun capitalize(fieldName: String): String {
        return fieldName.substring(0, 1).uppercase() + fieldName.substring(1)
    }

    fun asGetterName(fieldName: String): String {
        return PROPERTY_GETTER_PREFIX + capitalize(fieldName)
    }

    fun asSetterName(fieldName: String): String {
        return PROPERTY_SETTER_PREFIX + capitalize(fieldName)
    }

    fun <T, V> Method.asPropertyGetter(): PropertyGetter<T, V>? {
        if (parameterCount == 0 && returnType != Void.TYPE) {
            return MethodPropertyGetter(SimpleMethodAccessor(this))
        }
        return null
    }

    fun <T, V> Field.asPropertyGetter(): PropertyGetter<T, V> {
        val getterName = asGetterName(name)
        try {
            val getterMethod = declaringClass.getDeclaredMethod(getterName)
            if (getterMethod.returnType == this.type) {
                getterMethod.asPropertyGetter<T, V>()?.let {
                    return it
                }
            }
        } catch (e: NoSuchMethodException) {
            if (LOG.isDebugEnabled) {
                LOG.debug("AsPropertyGetter: Getter[$getterName] for field[$this] not found")
            }
        }
        return FieldPropertyGetter(FieldAccessor(this))
    }

    fun <T, V> Method.asPropertySetter(): PropertySetter<T, V>? {
        if (parameterCount == 1 && returnType == Void.TYPE) {
            return MethodPropertySetter(SimpleMethodAccessor(this))
        }
        return null
    }

    fun <T, V> Field.asPropertySetter(): PropertySetter<T, V>? {
        val setterName = asSetterName(name)
        try {
            val setterMethod = declaringClass.getDeclaredMethod(setterName)
            if (setterMethod.parameterTypes.first() == type) {
                setterMethod.asPropertySetter<T, V>()?.let {
                    return it
                }
            }
        } catch (e: NoSuchMethodException) {
            if (LOG.isDebugEnabled) {
                LOG.debug("AsPropertySetter: Setter[$setterName] for field[$this] not found")
            }
        }
        if (Modifier.isFinal(modifiers)) {
            if (LOG.isDebugEnabled) {
                LOG.debug("AsPropertySetter: Field[$this] is Final,can not set value.")
            }
            return null
        }
        return FieldPropertySetter(FieldAccessor(this))
    }
}
