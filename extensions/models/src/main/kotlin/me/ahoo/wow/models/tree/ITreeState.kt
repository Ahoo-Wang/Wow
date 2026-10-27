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

package me.ahoo.wow.models.tree

import java.util.SortedSet

/**
 * The state of a tree: its nodes, flat and ordered ([Info.compareTo]).
 *
 * 9.x extended `me.ahoo.wow.api.Identifier`, a part of 9.x wow-api the 10.x wow-api leaves out, so this declares [id]
 * itself: a state built with the aggregate's id (`(id: String)`).
 */
interface ITreeState<F : Flat> {
    val id: String
    val children: SortedSet<F>
}
