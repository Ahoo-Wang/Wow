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
 * Marks a service provider interface: a declaration that storage, transport and query backend implementations build
 * on, not one that applications call. It is public and keeps its binary signature within a minor line, but a minor
 * release (`x.Y.0`) may change it, with the change named in the release notes. Using it requires
 * `@OptIn(WowSpi::class)` (or the `-opt-in=me.ahoo.wow.api.annotation.WowSpi` compiler option); without it the
 * compiler warns.
 *
 * Declarations that are not even an extension point carry [InternalWowApi] instead.
 */
@RequiresOptIn(
    message = "Wow SPI for backend and transport implementations: a minor release may change it.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
annotation class WowSpi
