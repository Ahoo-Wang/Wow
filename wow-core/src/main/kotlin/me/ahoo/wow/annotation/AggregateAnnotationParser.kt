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
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.asPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import java.lang.reflect.Field
import java.lang.reflect.Method

object AggregateAnnotationParser {

    fun <T> Field.asStringGetter(): PropertyGetter<T, String> {
        require(type == String::class.java) {
            "Field[$this] must be of type String."
        }
        return asPropertyGetter()
    }

    fun <T> Method.asStringGetter(): PropertyGetter<T, String>? {
        require(returnType == String::class.java) {
            "Method[$this]'s returnType must be of type String."
        }
        return asPropertyGetter()
    }

    fun <T> Field.asIntGetter(): PropertyGetter<T, Int?> {
        require(type == Int::class.java || type == java.lang.Integer::class.java) {
            "Field[$this] must be of type Int."
        }
        return asPropertyGetter()
    }

    fun <T> Method.asIntGetter(): PropertyGetter<T, Int?>? {
        require(returnType == Int::class.java || returnType == java.lang.Integer::class.java) {
            "Method[$this]'s returnType must be of type Int."
        }
        return asPropertyGetter()
    }

    fun <T> Field.asAggregateNameGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateName>()?.let {
            this.asStringGetter()
        }
    }

    fun <T> Method.asAggregateNameGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateName>()?.let {
            this.asStringGetter()
        }
    }

    fun <T> Field.asAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateId>()?.let {
            this.asStringGetter()
        }
    }

    fun <T> Method.asAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<AggregateId>()?.let {
            this.asStringGetter()
        }
    }

    fun <T> Class<*>.asStaticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<StaticAggregateId>()?.let { staticAggregateId ->
            PropertyGetter { staticAggregateId.aggregateId }
        }
    }

    inline fun <reified T> staticAggregateIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return T::class.java.asStaticAggregateIdGetterIfAnnotated()
    }

    fun <T> Field.asTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<TenantId>()?.let {
            this.asStringGetter()
        }
    }

    fun <T> Class<*>.asStaticTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<StaticTenantId>()?.let { staticTenantId ->
            PropertyGetter { staticTenantId.tenantId }
        }
    }

    inline fun <reified T> staticTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return T::class.java.asStaticTenantIdGetterIfAnnotated()
    }

    fun <T> Method.asTenantIdGetterIfAnnotated(): PropertyGetter<T, String>? {
        return this.scan<TenantId>()?.let {
            this.asStringGetter()
        }
    }

    fun <T> Field.asAggregateVersionGetterIfAnnotated(): PropertyGetter<T, Int?>? {
        return this.scan<AggregateVersion>()?.let {
            this.asIntGetter()
        }
    }

    fun <T> Method.asAggregateVersionGetterIfAnnotated(): PropertyGetter<T, Int?>? {
        return this.scan<AggregateVersion>()?.let {
            this.asIntGetter()
        }
    }
}
