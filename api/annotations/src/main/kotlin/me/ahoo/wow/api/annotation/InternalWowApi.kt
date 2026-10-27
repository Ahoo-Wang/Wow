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

package me.ahoo.wow.api.annotation

/**
 * Internal to Wow: public for Wow's own modules only, and it may change or go in any release.
 *
 * Declarations marked with it are left out of the ABI dumps (`api/<module>.api`), so changing them is not a binary
 * break. Only Wow's own modules opt in; an application, the eShop example and the platform services do not.
 * Mark a constructor to keep a public class while hiding how it is built.
 */
@RequiresOptIn(
    message = "Internal to Wow: it may change or be removed in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
annotation class InternalWowApi
