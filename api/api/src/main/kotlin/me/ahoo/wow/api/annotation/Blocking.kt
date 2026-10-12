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
 * A synchronous function that blocks, such as one writing a projection over JDBC (V10, design 01 §4). A synchronous
 * function is otherwise taken for pure computation and runs inline on a shared worker; the generated call dispatches
 * this one to the blocking dispatcher, on virtual threads.
 */
@Target(AnnotationTarget.FUNCTION)
@Inherited
@MustBeDocumented
annotation class Blocking
