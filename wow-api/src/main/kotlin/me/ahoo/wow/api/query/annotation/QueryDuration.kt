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

/**
 * Declares a numeric field a length of time counted in [unit], so query consumers format it as a duration:
 * `@field:QueryDuration(TimeUnit.SECONDS)`. Never inferred: an `Int` does not tell its unit.
 * A field has one semantic type, so this cannot combine with [QueryTemporal], [QueryDecimal], [QueryMoney] or
 * [QueryReference].
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class QueryDuration(val unit: TimeUnit)
