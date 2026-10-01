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

package me.ahoo.wow.api.query.schema

import java.util.concurrent.TimeUnit

/**
 * The 9.1 temporal annotation: an integer field holding an epoch timestamp in [timeUnit]. A domain jar compiled
 * against 9.1 still names it, and the JVM silently drops an annotation whose class is missing, so schema discovery
 * keeps reading it as `@QueryTemporal(unit = timeUnit)` of `me.ahoo.wow.api.query.annotation`.
 */
@Deprecated(
    "Scheduled for removal in 10.0.0. Use me.ahoo.wow.api.query.annotation.QueryTemporal.",
    ReplaceWith("QueryTemporal(unit = timeUnit)", "me.ahoo.wow.api.query.annotation.QueryTemporal"),
)
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryTemporal(
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
)
