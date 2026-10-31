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
 * A sourcing function of an aggregate's state: it applies one event to the state. A function named
 * `onSourcing` needs no annotation.
 *
 * It takes the event, and may take the commit the event belongs to second ([me.ahoo.wow.api.read.EventStream]): its
 * commit time, operator and version, as the log records them. The kernel gives the same commit deciding and
 * replaying, from the log or from a snapshot's tail, so a state that reads a time reads it from the commit, never
 * from a clock, and a rebuild reads the same state:
 *
 * ```kotlin
 * fun onSourcing(event: OrderPaid, commit: EventStream) {
 *     paidAt = commit.commitTime
 *     paidBy = commit.operator?.id
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnSourcing
