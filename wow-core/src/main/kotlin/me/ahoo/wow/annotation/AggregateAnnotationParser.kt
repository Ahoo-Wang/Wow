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
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.accessor.property.StaticPropertyGetter
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import java.lang.reflect.Field
import java.lang.reflect.Method

object AggregateAnnotationParser {

    fun <T> Field.toStringGetter(): PropertyGetter<T, String> {
        require(type == String::class.java) {
            "Field[$this] must be of type String."
        }
        return toPropertyGetter()
    }

    fun <T> Method.toStringGetter(): PropertyGetter<T, String>? {
        require(returnType == String::class.java) {
            "Method[$this]'s returnType must be of type String."
        }
        return toPropertyGetter()
    }

    fun <T> Field.toIntGetter(): PropertyGetter<T, Int?> {
        require(type == Int::class.java || type == java.lang.Integer::class.java) {
            "Field[$this] must be of type Int."
        }
        return toPropertyGetter()
    }

    fun <T> Method.toIntGetter(): PropertyGetter<T, Int?>? {
        require(returnType == Int::class.java || returnType == java.lang.Integer::class.java) {
            "Method[$this]'s returnType must be of type Int."
        }
        return toPropertyGetter()
    }

    fun <T> Field.toAggregateNameGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateName>()?.let {
            this.toStringGetter()
        }
    }

    fun <T> Method.toAggregateNameGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateName>()?.let {
            this.toStringGetter()
        }
    }

    fun <T> Field.toAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateId>()?.let {
            this.toStringGetter()
        }
    }

    fun <T> Method.toAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateId>()?.let {
            this.toStringGetter()
        }
    }

    fun <T> Class<*>.toStaticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<StaticAggregateId>()?.let { staticAggregateId ->
            StaticPropertyGetter(staticAggregateId.aggregateId)
        }
    }

    inline fun <reified T> staticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return T::class.java.toStaticAggregateIdGetterIfAnnotated()
    }

    fun <T> Field.toTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<TenantId>()?.let {
            this.toStringGetter()
        }
    }

    fun <T> Class<*>.toStaticTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<StaticTenantId>()?.let { staticTenantId ->
            StaticPropertyGetter(staticTenantId.tenantId)
        }
    }

    inline fun <reified T> staticTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return T::class.java.toStaticTenantIdGetterIfAnnotated()
    }

    fun <T> Method.toTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<TenantId>()?.let {
            this.toStringGetter()
        }
    }

    fun <T> Field.toAggregateVersionGetterIfAnnotated(): PropertyGetter<T, Int?>? {
        return this.scan<AggregateVersion>()?.let {
            this.toIntGetter()
        }
    }

    fun <T> Method.toAggregateVersionGetterIfAnnotated(): PropertyGetter<T, Int?>? {
        return this.scan<AggregateVersion>()?.let {
            this.toIntGetter()
        }
    }
}
