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
 * The markers of `requires` this reader understands: a marker names a change in what part of a Manifest means, which a
 * reader that does not know it would misread, so [ManifestTree.read] refuses a Manifest that requires one outside
 * [KNOWN], wherever it stands: on the Manifest ([Manifest.requires]), on a context ([ContextManifest.requires]) or on an
 * aggregate ([AggregateManifest.requires]). A value that only widens a set, such as a new enum value, needs no marker:
 * readers read it leniently. Format 1 has none yet. One set serves every level.
 */
object ManifestFeatures {
    /** The markers this reader understands. */
    val KNOWN: Set<String> = emptySet()

    /**
     * The markers of several `requires` united, sorted and distinct: what a projection (a merge, a contract) keeps where
     * it makes one node of several, since a reader must understand what any of them requires. A marker never moves to
     * another level.
     */
    @InternalWowApi
    fun union(requires: List<List<String>>): List<String> = requires.flatten().distinct().sorted()

    /**
     * Refuses, an [IllegalArgumentException], the [requires] of [where] (`the Manifest`, `the context shop`, `the
     * aggregate shop.cart`) that hold a marker outside [KNOWN], naming the level, the node and the markers.
     */
    @InternalWowApi
    fun check(where: String, requires: List<String>) {
        val unknown = requires.filterNot(KNOWN::contains).distinct().sorted()
        require(unknown.isEmpty()) {
            "${where.replaceFirstChar(Char::uppercaseChar)} requires ${unknown.joinToString()}, which this reader does " +
                "not understand: read the Manifest with a Wow whose wow-manifest does (upgrade the node, the Gradle " +
                "plugin or the tool that reads it)."
        }
    }
}
