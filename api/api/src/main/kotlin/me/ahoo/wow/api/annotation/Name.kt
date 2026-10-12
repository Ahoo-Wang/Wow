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
 * The name of an aggregate, a processor, a command or an event (V10, decision D55), in place of the one derived
 * from the class's simple name. Commands and events are typed `context.aggregate.Name`.
 *
 * @param value the name.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
@Inherited
@MustBeDocumented
annotation class Name(
    val value: String,
)
