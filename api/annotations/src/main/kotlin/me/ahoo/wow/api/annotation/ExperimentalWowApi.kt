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
 * Experimental in Wow: a public surface that a 10.x minor release may still change or remove, such as dataset
 * queries, the HTTP operations routes and webhooks, and ABAC query policies (D216).
 *
 * Unlike [InternalWowApi], declarations marked with it stay in the ABI dumps (`api/<module>.api`), so a change shows in
 * review; a user opts in, and gets a compiler warning otherwise.
 */
@RequiresOptIn(
    message = "Experimental Wow API: a 10.x minor release may change or remove it.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
annotation class ExperimentalWowApi
