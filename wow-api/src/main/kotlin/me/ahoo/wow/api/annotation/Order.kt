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
import kotlin.reflect.KClass

const val ORDER_FIRST = Int.MIN_VALUE
const val ORDER_DEFAULT = 0
const val ORDER_DEFAULT_STEP = 100
const val ORDER_LAST = Int.MAX_VALUE

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class Order(
    val value: Int = ORDER_DEFAULT,
    val before: Array<KClass<*>> = [],
    val after: Array<KClass<*>> = []
)
