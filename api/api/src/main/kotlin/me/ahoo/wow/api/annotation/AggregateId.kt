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
 * The property of a command that holds the id of the aggregate the command acts on, its target: a `String` the
 * command's class declares, or an interface it implements does, stored or computed from other properties. The
 * compiler generates its read, and the Manifest names it (`idProperty`), so:
 *
 * - the command's routes name no id (`POST /{context}/{aggregate}/{command}`): the request names its aggregate in its
 *   body, and one that names it elsewhere as well (a ref beside the body) names the same one, or is malformed;
 * - a saga, a client or another context sends it without an id, which its body gives;
 * - a command that creates its aggregate may leave it null: the aggregate's id is then generated, as for any creation
 *   that names none; a blank id is malformed.
 *
 * The id is in the body every sender writes: the property is one Wow's JSON writes, not `@JsonIgnore`d. A sender
 * that sends some properties alone (a form, a console) may leave a computed one out: the service computes it from the
 * command. One property of a command is its aggregate id; an aggregate whose id is its owner's
 * (`@AggregateRoot(idSource = OWNER)`) takes none, and neither does an `@Unaddressed` or a mounted command, whose
 * transformer finds the aggregate.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@MustBeDocumented
annotation class AggregateId
