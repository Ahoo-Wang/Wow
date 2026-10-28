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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.EntityTags.Condition
import me.ahoo.wow.http.EntityTags.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EntityTagsTest {
    @Test
    fun `a state is tagged by its version and the digest of the body read`() {
        val tag = EntityTags.state(3, "{}".toByteArray())
        tag.matches(Regex("\"3-[0-9a-f]{16}\"")).assert().isTrue()
        EntityTags.state(3, "{\"a\":1}".toByteArray()).assert().isNotEqualTo(tag)
        // The tag a state read writes is the tag a command takes back: its version.
        EntityTags.versions((EntityTags.parse(tag) as Condition.Listed).tags).assert().containsExactly(3)
    }

    @Test
    fun `a header is a star or a list of tags, weak or strong`() {
        EntityTags.parse("*").assert().isEqualTo(Condition.Any)
        EntityTags.parse(" \"3\" ").assert().isEqualTo(Condition.Listed(listOf(Tag("\"3\"", false))))
        EntityTags.parse("\"3\", W/\"4-00ff\",,\"x\"").assert().isEqualTo(
            Condition.Listed(listOf(Tag("\"3\"", false), Tag("\"4-00ff\"", true), Tag("\"x\"", false))),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "3", "\"3", "\"3\" \"4\"", "*, \"3\"", "w/\"3\"", "\"a b\"", ","])
    fun `anything else does not parse`(value: String) {
        EntityTags.parse(value).assert().isNull()
    }

    @Test
    fun `If-Match compares strongly, a tag naming a version alone or as a state read tags it`() {
        val header = "\"3\", \"5-0123456789abcdef\", W/\"7\", \"x\", \"9-short\""
        val tags = (EntityTags.parse(header) as Condition.Listed).tags
        EntityTags.versions(tags).assert().containsExactlyInAnyOrder(3, 5)
        EntityTags.versions(listOf(Tag("\"4\"", true))).assert().isEmpty()
        EntityTags.versions(listOf(Tag("\"03\"", false), Tag("\"99999999999\"", false))).assert().isEmpty()
    }

    @Test
    fun `If-None-Match compares weakly, and a star matches any tag`() {
        val tag = "\"2-0123456789abcdef\""
        EntityTags.matchesWeakly(EntityTags.parse("W/$tag")!!, tag).assert().isTrue()
        EntityTags.matchesWeakly(EntityTags.parse("\"1\", $tag")!!, tag).assert().isTrue()
        EntityTags.matchesWeakly(Condition.Any, tag).assert().isTrue()
        EntityTags.matchesWeakly(EntityTags.parse("\"2\"")!!, tag).assert().isFalse()
    }

    @Test
    fun `a command's If-Match asks one version, no condition, or is refused`() {
        val oneVersion = "\"4-0123456789abcdef\", \"4\", W/\"9\""
        EntityTags.ifMatch(oneVersion, creates = false).assert().isEqualTo(EntityTags.IfMatch.Version(4))
        EntityTags.ifMatch("*", creates = false).assert().isEqualTo(EntityTags.IfMatch.Unconditional)
        // A creation meets no *, and tags naming no version meet nothing: 412.
        refusal("*", creates = true).malformed.assert().isFalse()
        refusal("W/\"4\", \"x\"").malformed.assert().isFalse()
        // Two versions, or a header that does not parse: 400.
        val two = refusal("\"4\", \"5\"")
        two.malformed.assert().isTrue()
        two.detail.assert().contains("4, 5")
        refusal("4").malformed.assert().isTrue()
    }

    private fun refusal(value: String, creates: Boolean = false): EntityTags.IfMatch.Refused =
        EntityTags.ifMatch(value, creates) as EntityTags.IfMatch.Refused
}
