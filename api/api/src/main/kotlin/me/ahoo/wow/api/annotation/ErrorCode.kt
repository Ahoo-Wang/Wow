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
 * A rejection code of the domain (V10, decision D121), on a `const val` of type `String`, top-level or in an
 * `object`, that holds the code a command function rejects with (`Decision.reject(code, message)`). The compiler lists
 * the codes of its module in the context's `errors` of the Manifest fragment, with [status] and [description]. In
 * Java it goes on a `static final String` field (decision D229 G-8).
 *
 * A code without a dot is a local name (decision D259), such as `"invalid-item"`: declare it in an `object` nested in
 * its `@AggregateRoot` class (or, in Java, on a field of that class or of a class nested in it). The compiler writes
 * it into the Manifest as `context.aggregate.code`, the kernel gives a rejection with it that name before the
 * command's result, receipt or HTTP answer carries it, and the context's api module gets it as a constant of its
 * generated contract (`<Context>Contract.<Aggregate>.Errors.INVALID_ITEM`) for its peers. A code with a dot is full, as
 * written: one the context shares among its aggregates (`context.code`), or one declared outside an aggregate.
 *
 * The compiler reads the code from the property's (or field's) initializer, which must be a string literal. A code may not take
 * the framework's `wow.` prefix, and a module declares each code once. Only the module the compiler processes is
 * read: the codes belong in the domain module, beside the functions that reject with them.
 *
 * @param description what the code means, for the Manifest's readers; empty for none.
 * @param status the HTTP status a rejection with the code is answered with, 400 to 599; 422 by default.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@MustBeDocumented
annotation class ErrorCode(
    val description: String = "",
    val status: Int = 422,
)
