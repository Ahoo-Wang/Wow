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
 * Marks code Wow generates: every class the compiler (KSP) and the contract tools declare carries it, the file facade
 * of generated top-level functions through `@file:WowGenerated`, and each nested object or class by itself.
 *
 * It is for coverage tools, which exclude generated code by it instead of by name. JaCoCo skips a class annotated with
 * an annotation of class or runtime retention whose simple name contains "Generated", by itself, with no
 * configuration; Kover excludes it with `annotatedBy("me.ahoo.wow.api.annotation.WowGenerated")`. A class the Kotlin
 * compiler synthesizes inside a generated function (a suspending lambda, the copy of an inline function's lambda)
 * carries no annotation of its own: under JaCoCo a project excludes those of the generated `<Root>ModelKt` and
 * `<Processor>SubscriptionsKt` facades by `*ModelKt$*` and `*SubscriptionsKt$*`.
 *
 * Kept in the class files and never loaded at run time ([AnnotationRetention.BINARY]): a class carrying it needs
 * nothing of Wow's at run time for it. Nothing in Wow reads it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
@MustBeDocumented
annotation class WowGenerated
