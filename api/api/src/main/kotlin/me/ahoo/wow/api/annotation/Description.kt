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
 * A description of a type or of a property: the `description` of its schema in the Manifest, on a class for the type's
 * schema and on a property for the property's, which the generated clients and the OpenAPI document carry as
 * documentation.
 *
 * A property takes it where each language lets it stand: on a Kotlin property, declared in the constructor or in the
 * body, or computed; on its field (`@field:Description`); on a Java field, and so on a record's component. A
 * property's description holds where the property is used, beside the reference to its type, whose own description
 * stays with the type. The value is trimmed, and a blank one says nothing.
 *
 * On the class of a command, an event or an aggregate it is also the description of its entry in the Manifest, ahead
 * of the class's KDoc. It is no summary: a schema's `title` is its [Summary]'s alone.
 *
 * @param value the description.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
@MustBeDocumented
annotation class Description(
    val value: String,
)
