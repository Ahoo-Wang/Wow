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

/**
 * The markers of [Manifest.requires] this reader understands: a marker names a change in what part of a Manifest
 * means, which a reader that does not know it would misread, so [ManifestTree.read] refuses a Manifest that requires
 * one outside [KNOWN]. A value that only widens a set, such as a new enum value, needs no marker: readers read it
 * leniently. Format 1 has none yet.
 */
object ManifestFeatures {
    /** The markers this reader understands. */
    val KNOWN: Set<String> = emptySet()
}
