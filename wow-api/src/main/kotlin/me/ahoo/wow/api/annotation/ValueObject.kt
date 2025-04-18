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
 * ValueObject annotation is used to mark a class or an annotation as a value object. A value object represents a domain concept that is defined by its data and does not have a distinct identity. This annotation can
 *  be applied to classes and annotation classes.
 *
 * The `@ValueObject` annotation is intended to convey the design intent that the annotated class should be treated as a value object, which typically means it should be immutable and compared
 *  based on its content rather than its identity.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class ValueObject
