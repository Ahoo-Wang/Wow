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

package me.ahoo.wow.annotation

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.TenantId
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.reflection.KAnnotationScanner.scanAnnotation
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

object AnnotationPropertyAccessorParser {

    fun <T> KProperty1<T, *>.toStringGetter(): PropertyGetter<T, String> {
        require(this.returnType.classifier == String::class) {
            "Property[$this] must be of type String."
        }
        @Suppress("UNCHECKED_CAST")
        return (this as KProperty1<T, String>).toPropertyGetter()
    }

    fun <T> KProperty1<T, *>.toIntGetter(): PropertyGetter<T, Int> {
        require(this.returnType.classifier == Int::class) {
            "Property[$this] must be of type Int."
        }
        @Suppress("UNCHECKED_CAST")
        return (this as KProperty1<T, Int>).toPropertyGetter()
    }

    fun <T> KProperty1<T, *>.toAggregateNameGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<AggregateName>()?.let {
            return toStringGetter()
        }
    }

    fun <T> KProperty1<T, *>.toAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<AggregateId>()?.let {
            return toStringGetter()
        }
    }

    fun <T : Any> KClass<T>.toStaticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<StaticAggregateId>()?.aggregateId?.toPropertyGetter()
    }

    inline fun <reified T : Any> staticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return T::class.toStaticAggregateIdGetterIfAnnotated()
    }

    fun <T> KProperty1<T, *>.toTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scanAnnotation<TenantId>()?.let {
            return toStringGetter()
        }
    }

    fun <T> KProperty1<T, *>.toAggregateVersionGetterIfAnnotated(): PropertyGetter<T, Int>? {
        return this.scanAnnotation<AggregateVersion>()?.let {
            return toIntGetter()
        }
    }
}
