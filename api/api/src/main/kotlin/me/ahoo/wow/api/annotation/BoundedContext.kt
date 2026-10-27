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

import java.lang.annotation.Inherited

/**
 * The bounded context a domain module belongs to. The runtime binds the context when it assembles
 * the Manifest fragments, so a module rarely carries it; when it does, its [name] and [description] go into the
 * Manifest.
 *
 * @param name the context's name, unique within the service: lower-case letters, digits and hyphens, starting with a
 *   letter (`[a-z][a-z0-9-]*`), such as `eshop-catalog`. A dot is never part of it, since it separates
 *   the context from the aggregate in type names (`context.aggregate.Command`); the compiler refuses such a name.
 * @param description what the context is for, for documentation.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class BoundedContext(
    val name: String,
    val description: String = "",
)
