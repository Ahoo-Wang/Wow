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
 * A one-line summary of a type or of a property: the `title` of its schema in the Manifest, on a class for the type's
 * schema and on a property for the property's, which the generated clients and the OpenAPI document carry as
 * documentation.
 *
 * A property takes it where each language lets it stand: on a Kotlin property, declared in the constructor or in the
 * body, or computed; on its field (`@field:Summary`); on a Java field, and so on a record's component. A property's
 * summary holds where the property is used, beside the reference to its type, whose own summary stays with the
 * type. The value is trimmed, and a blank one says nothing.
 *
 * It is no description: a schema's `description` is its [Description]'s alone, and a schema with a summary only has
 * a `title` and no `description`. Only the entry of a command, an event or an aggregate in the Manifest, which has a
 * description and no summary, takes the summary when the class has neither a [Description] nor a KDoc.
 *
 * @param value the summary.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
@MustBeDocumented
annotation class Summary(
    val value: String,
)
