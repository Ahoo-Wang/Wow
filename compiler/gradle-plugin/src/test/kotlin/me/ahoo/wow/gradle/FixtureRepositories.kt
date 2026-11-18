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

package me.ahoo.wow.gradle

/**
 * The remote repositories of the TestKit fixtures' builds, in a line that reads alike in the Kotlin and the Groovy DSL.
 * A remote repository is never asked for [buildGroups]: Wow's modules come from the build under test (its files and
 * the fixture repository), so a test cannot pass because the version under test is on Maven Central, and the
 * fixtures' own modules are only in the repositories the fixtures publish to.
 */
internal object FixtureRepositories {
    /** Wow's group, which the build supplies, and the groups the fixtures publish themselves. */
    val buildGroups = listOf("me.ahoo.wow", "repro", "sample")

    private val content = buildGroups.joinToString("; ", "content { ", " }") { "excludeGroup(\"$it\")" }

    /** Maven Central, for everything but [buildGroups]. */
    val central = "mavenCentral { $content }"

    /** A remote repository at [url], declared as the fixtures declare [central]. */
    fun remote(url: String): String = "maven { url = uri(\"$url\"); $content }"
}
