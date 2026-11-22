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

package me.ahoo.wow.runtime.sink

import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.spi.read.StateMasks

/**
 * What the transport sink masks the streams it publishes by: the node's [masks], as a read applies them, and whether
 * the service shares its `DISPLAY` values outside itself at all ([displayShared], `wow.query.sensitivity.display-shared`).
 *
 * A stream's `CONFIDENTIAL` values are always masked, and its `DISPLAY` values too unless it reveals them
 * ([revealsDisplay]): the owner declares it (`@AggregateRoot(revealsDisplay = true)`, [PublishedManifest.revealsDisplay])
 * and the service shares them. The switch only tightens: off, a stream declared to reveal them is masked all the same.
 */
class PublicationMasks(val masks: StateMasks, val displayShared: Boolean) {
    /** Whether [stream] is published with its `DISPLAY` values raw: declared so, and shared. */
    fun revealsDisplay(stream: PublishedManifest): Boolean = stream.revealsDisplay && displayShared

    override fun toString(): String = "PublicationMasks(masks=$masks, displayShared=$displayShared)"
}
