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

package me.ahoo.wow.query.backend

/**
 * Marks the additive Backend-facing Query Plan and execution SPI.
 *
 * Backends receive only immutable, validated logical plans. They must not accept wire query DTOs, driver-native input,
 * or infer physical fields from logical names.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "The Query Backend SPI is experimental and may evolve before the next major release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class ExperimentalQueryBackendApi
