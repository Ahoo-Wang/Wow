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

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A fragment and where it was read from, for the messages of [ManifestMerger].
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class ManifestFragment @InternalWowApi constructor(val source: String, val manifest: Manifest) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ManifestFragment && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ManifestFragment(source=$source, manifest=$manifest)"

    private fun valueFields(): List<Any?> = listOf(source, manifest)
}
