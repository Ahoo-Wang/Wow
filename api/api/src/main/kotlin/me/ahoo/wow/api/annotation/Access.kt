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
 * Marks a member of an aggregate's state as one of the aggregate's access attributes: a fact of the state that decides
 * who may read and change the aggregate, which the service's access policies compare with the caller's claims.
 *
 * ```kotlin
 * class DocumentState {
 *     @Access val department: String = ""
 *     @Access val sharedWith: Set<String> = emptySet()
 *     @Access("org") val orgPath: String = "" // A materialized path, such as "/acme/sales/east".
 *     var title: String = ""
 * }
 * ```
 *
 * - **What it marks**: a property of the state class (stored or computed), or a function of it without parameters,
 *   inherited ones included, neither private nor an extension, since the generated model reads it.
 * - **A pure, cheap function of the state**: the runtime derives every attribute on each commit, so the member reads
 *   the state alone and does little work. It does no I/O, reads no clock and draws no random number; the compiler
 *   refuses one that suspends or is `@Blocking`.
 * - **Its value**: a `String`, an `Int`, a `Long`, a `Boolean` or an enum, nullable for none, or a value class that
 *   wraps one of them (`@JvmInline value class DepartmentId(val value: String)`), published as the value it wraps;
 *   or a `List`, `Set` or `Collection` of them, neither the collection nor its elements nullable, for an attribute of
 *   several values. An enum is published as the name Jackson writes for its constant.
 * - **Its name**: [name], else the member's name (a function's name as written). It is how the Manifest, the
 *   generated attribute references and the stored records name the attribute: lower camel case, a lower-case letter
 *   then letters and digits, at most 40 characters (`me.ahoo.wow.manifest.AccessAttributeName`), unique within the
 *   aggregate ignoring case, and neither `owner` nor `space`, the built-in attributes every aggregate has.
 *
 * The compiler writes each attribute into the Manifest (its name, type, and whether it holds several values) and
 * generates `<State>Access`, an object with a typed reference to each, beside the state. An attribute changes only as
 * the state does: to grant access, write a domain command and its event (`ShareDocument`, `DocumentShared`), which
 * the domain checks and the log records.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class Access(
    val name: String = "",
)
