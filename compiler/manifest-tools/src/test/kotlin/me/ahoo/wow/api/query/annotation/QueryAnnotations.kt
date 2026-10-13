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

package me.ahoo.wow.api.query.annotation

import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/*
 * The query field annotations of wow-query-api (api/query-api), declared again for the fixtures: the V10 module rules
 * do not let manifest-tools depend on :query-api, so it reads these annotations by their class names. Each mirror has
 * the same name, targets, retention, members and defaults as the original.
 */

enum class SensitivityLevel { DISPLAY, CONFIDENTIAL }

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Sensitive(val level: SensitivityLevel, val mask: Mask = Mask())

@Target
@Retention(AnnotationRetention.RUNTIME)
annotation class Mask(
    val keepPrefix: Int = 0,
    val keepSuffix: Int = 0,
    val strategy: KClass<out MaskStrategy> = MaskStrategy::class,
)

fun interface MaskStrategy {
    fun mask(value: String): String
}

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryAlias(vararg val value: String)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryTemporal(val unit: TimeUnit = TimeUnit.MILLISECONDS, val pattern: String = "")

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryDecimal(val scale: Int)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryMoney(val currency: String = "", val currencyField: String = "", val scale: Int = -1)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryDuration(val unit: TimeUnit)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryReference(val aggregateName: String, val contextName: String = "")

@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class QueryIndex(val name: String = "", val on: String = "", val with: Array<String> = [])
