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
 * A command mounted under an aggregate's routes that no aggregate decides (V10, decision D182): it has no `@OnCommand`,
 * and exactly one `CommandTransformer` of the service claims it, turning it into a command an aggregate decides, such
 * as an import that becomes the aggregate's creation command or an external callback in a provider's shape. The
 * Manifest lists it under [aggregate] as a mounted command, with its routes (with and without an aggregate id), and
 * the service refuses to start when no transformer, or more than one, claims it.
 *
 * The model processor finds mounted commands among the classes of the module it compiles, so a mounted command lives
 * in the domain module of its aggregate. Its `@Name` names it within its aggregate, as it names a command.
 *
 * @param aggregate the name of the aggregate, in the module's bounded context, whose routes the command is mounted
 *   under.
 * @param transformsTo the commands the transformer turns it into, each by its full type name
 *   `context.aggregate.Command`, which the Manifest lists as the command's `transformsTo`. Empty, the default, declares
 *   none.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class MountedCommand(
    val aggregate: String,
    val transformsTo: Array<String> = [],
)
