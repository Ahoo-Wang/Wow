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

package me.ahoo.wow.elasticsearch

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdLimits
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchNames.Companion.MAX_DOCUMENT_ID_BYTES
import org.junit.jupiter.api.Test

/**
 * A document's `_id` within the 512 bytes Elasticsearch takes, whatever the tenant and the aggregate id: an id the
 * aggregate id rule takes (512 bytes) behind a tenant of its own is longer, and was refused by Elasticsearch before.
 */
class ElasticsearchDocumentIdsTest {
    private val type = AggregateType("ctx", "order")

    private fun bytesOf(id: String) = AggregateIdLimits.utf8Length(id)

    private val boundedShape = Regex(".*/[0-9a-f]{64}")

    @Test
    fun `a short id should be the id it always was, to the byte`() {
        ElasticsearchNames.documentId(type.ref("t:1", "a")).assert().isEqualTo("3:t:1:a")
        ElasticsearchNames.documentId(type.ref("t", "购物车")).assert().isEqualTo("1:t:购物车")
        ElasticsearchNames.streamId(type.ref("t", "a"), 7).assert().isEqualTo("1:t:a@7")
    }

    @Test
    fun `an id of exactly the bytes Elasticsearch takes should be kept as it is`() {
        // "3:t:1:" is 6 bytes: 506 more make 512.
        val id = "x".repeat(MAX_DOCUMENT_ID_BYTES - 6)
        ElasticsearchNames.documentId(type.ref("t:1", id)).assert().isEqualTo("3:t:1:$id")
        // Two-byte characters to the last byte.
        val accented = "é".repeat((MAX_DOCUMENT_ID_BYTES - 6) / 2)
        ElasticsearchNames.documentId(type.ref("t:1", accented)).assert().isEqualTo("3:t:1:$accented")
    }

    @Test
    fun `an id one byte longer should be bounded, its prefix kept and the digest of the whole appended`() {
        val id = "x".repeat(MAX_DOCUMENT_ID_BYTES - 5)
        val documentId = ElasticsearchNames.documentId(type.ref("t:1", id))
        bytesOf(documentId).assert().isEqualTo(MAX_DOCUMENT_ID_BYTES)
        documentId.assert().matches(boundedShape.toPattern())
        documentId.assert().startsWith("3:t:1:xxx")
        // The longest id the rule takes, behind a long tenant: refused by Elasticsearch before.
        val longest = ElasticsearchNames.documentId(type.ref("t".repeat(64), "购".repeat(170)))
        bytesOf(longest).assert().isLessThanOrEqualTo(MAX_DOCUMENT_ID_BYTES)
        longest.assert().matches(boundedShape.toPattern())
    }

    @Test
    fun `two long ids sharing their prefix should stay distinct, and each be the same every time`() {
        val shared = "x".repeat(MAX_DOCUMENT_ID_BYTES)
        val one = ElasticsearchNames.documentId(type.ref("t", shared + "a"))
        val other = ElasticsearchNames.documentId(type.ref("t", shared + "b"))
        one.assert().isNotEqualTo(other)
        one.substringBefore('/').assert().isEqualTo(other.substringBefore('/'))
        ElasticsearchNames.documentId(type.ref("t", shared + "a")).assert().isEqualTo(one)
        // A lone surrogate past the prefix is not the '?' an encoder to UTF-8 writes for it.
        ElasticsearchNames.documentId(type.ref("t", shared + "\uD83D")).assert()
            .isNotEqualTo(ElasticsearchNames.documentId(type.ref("t", "$shared?")))
        // The tenant is part of the identity: the same id of another tenant is another document.
        ElasticsearchNames.documentId(type.ref("u", shared + "a")).assert().isNotEqualTo(one)
    }

    @Test
    fun `a bounded id should never cut a character of two UTF-16 units`() {
        // "200:<tenant>:" is 205 bytes, each emoji 4: the 447 bytes of prefix end 2 bytes into the 61st emoji.
        val documentId = ElasticsearchNames.documentId(type.ref("t".repeat(200), "😀".repeat(128)))
        val prefix = documentId.substringBeforeLast('/')
        prefix.assert().isEqualTo("200:" + "t".repeat(200) + ":" + "😀".repeat(60))
        bytesOf(documentId).assert().isLessThanOrEqualTo(MAX_DOCUMENT_ID_BYTES)
    }

    @Test
    fun `an event stream's id should be bounded the same way, its version part of the digest`() {
        val ref = type.ref("t".repeat(64), "x".repeat(AggregateIdLimits.MAX_BYTES))
        val first = ElasticsearchNames.streamId(ref, 1)
        val second = ElasticsearchNames.streamId(ref, 2)
        bytesOf(first).assert().isEqualTo(MAX_DOCUMENT_ID_BYTES)
        first.assert().matches(boundedShape.toPattern())
        first.assert().isNotEqualTo(second)
        // A stream's id is never its snapshot's.
        first.assert().isNotEqualTo(ElasticsearchNames.documentId(ref))
    }
}
