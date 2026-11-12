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
 * The property of a command that holds when what the command records occurred: a `Long` of epoch milliseconds the
 * command's class declares, or `Long?`, whose null declares nothing. A commit has two times: when what it records
 * occurred, its commit time, which a state, a snapshot's first and last event times, a query and the warehouse read;
 * and when it was written, the same unless the command declares the first. Its decide function's `scope.now` and its
 * commit's time are then the declared time, and the time it was written is kept in the commit's
 * `wow.commit.written-at` header, for an audit, never propagated to the commands derived from it.
 *
 * In 10.0 only a command that creates its aggregate ([CreateAggregate], `Creation.REQUIRED`) declares one: an import
 * from another system, whose aggregate's `firstEventTime` is then when it was created there. When it entered Wow is
 * the import's own fact: the import program gives it in the command's body, and the import event keeps it.
 *
 * A time later than the node's clock, or before the epoch, is refused (`wow.command.invalid`). One property of a
 * command declares it: the compiler refuses one on a command that does not create its aggregate, on a type other than
 * `Long`, and on a property Wow's JSON does not write. Whoever may send the command chooses the time, so an import
 * command is usually internal ([InternalCommand]).
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@MustBeDocumented
annotation class OccurredAt
