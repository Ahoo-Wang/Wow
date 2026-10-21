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
 * A function that blocks its thread, such as one writing a projection or checking a uniqueness over JDBC (design
 * 01 §4). A synchronous function is otherwise taken for pure computation and runs inline on a shared worker; the
 * generated call runs this one on the blocking dispatcher, a virtual thread per call. It applies to command functions
 * (`@OnCommand`), after hooks (`@AfterCommand`), subscription handlers (`@OnEvent`, `@OnRejected`, `@OnCommitted`) and
 * scheduled jobs (`@OnSchedule`), also when it is on the function an override overrides. Sourcing functions, `@Upgrade`
 * functions and `@Tags` members do no I/O, so it is a compile error on them; on a function returning a Reactor
 * publisher it is a warning, since a publisher should not block.
 */
@Target(AnnotationTarget.FUNCTION)
@Inherited
@MustBeDocumented
annotation class Blocking
