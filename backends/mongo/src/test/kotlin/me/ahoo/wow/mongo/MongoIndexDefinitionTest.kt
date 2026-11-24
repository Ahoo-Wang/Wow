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

package me.ahoo.wow.mongo

import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.bson.Document
import org.junit.jupiter.api.Test

/** An index as Wow creates it against what `listIndexes` reports under its name (#6748). */
class MongoIndexDefinitionTest {
    private val definition = MongoIndexDefinition(
        Indexes.ascending("partition", "version"),
        IndexOptions().name("version_key").unique(true).partialFilterExpression(Filters.exists("version")),
    )

    /** `listIndexes`' report of an index named `version_key`, as Wow creates it unless [changed] says otherwise. */
    private fun reported(changed: Document.() -> Unit = {}) = Document("v", 2)
        .append("key", Document("partition", 1).append("version", 1))
        .append("name", "version_key")
        .append("unique", true)
        .append("partialFilterExpression", Document("version", Document("\$exists", true)))
        .apply(changed)

    @Test
    fun `the index as Wow creates it has no difference, its directions whatever their number type`() {
        definition.differences(reported()).assert().isEmpty()
        definition.differences(reported { this["key"] = Document("partition", 1.0).append("version", 1L) })
            .assert().isEmpty()
        // A shell may write an option as a number.
        definition.differences(reported { this["unique"] = 1 }).assert().isEmpty()
    }

    @Test
    fun `each difference is named`() {
        definition.differences(reported { remove("unique") }).assert().containsExactly("it is not unique")
        definition.differences(reported { this["key"] = Document("version", 1).append("partition", 1) })
            .assert().containsExactly(
                "its key is {\"version\": 1, \"partition\": 1}, not {\"partition\": 1, \"version\": 1}"
            )
        definition.differences(reported { this["key"] = Document("partition", 1).append("version", -1) })
            .single().assert().startsWith("its key is")
        definition.differences(reported { remove("partialFilterExpression") })
            .assert().containsExactly("its partial filter is none, not {\"version\": {\"\$exists\": true}}")
        definition.differences(
            reported { this["sparse"] = true }
        ).assert().containsExactly("it is sparse, and Wow's is not")
        definition.differences(reported { this["collation"] = Document("locale", "en").append("strength", 2) })
            .assert().containsExactly("it has the collation {\"locale\": \"en\", \"strength\": 2}, and Wow's has none")
    }

    @Test
    fun `an index Wow creates without unique refuses a unique one, and a hashed key compares as its type`() {
        val hashed = MongoIndexDefinition(
            Indexes.compoundIndex(Indexes.ascending("tenant_id"), Indexes.hashed("key")),
            IndexOptions().name("by_key"),
        )
        val report = Document("key", Document("tenant_id", 1).append("key", "hashed")).append("name", "by_key")
        hashed.differences(report).assert().isEmpty()
        hashed.differences(
            Document(report).append("unique", true)
        ).assert().containsExactly("it is unique, and Wow's is not")
        hashed.differences(Document(report).append("key", Document("tenant_id", 1).append("key", 1)))
            .single().assert().startsWith("its key is")
    }

    @Test
    fun `an index of Wow's stores has a name`() {
        assertThrownBy<IllegalStateException> { MongoIndexDefinition(Indexes.ascending("partition"), IndexOptions()) }
            .hasMessage("An index of Wow's stores has a name.")
    }

    @Test
    fun `an index Wow creates sparse, or without a partial filter, is compared and specified as such`() {
        val sparse = MongoIndexDefinition(Indexes.ascending("key"), IndexOptions().name("by_key").sparse(true))
        val report = Document("key", Document("key", 1)).append("name", "by_key")
        sparse.differences(report).assert().containsExactly("it is not sparse")
        sparse.differences(Document(report).append("sparse", true)).assert().isEmpty()
        // A stored partial filter where Wow's has none, and one a tool wrote as no document at all.
        sparse.differences(
            Document(report).append("sparse", true).append("partialFilterExpression", Document("deleted", false))
        )
            .assert().containsExactly("its partial filter is {\"deleted\": false}, not none")
        definition.differences(reported { this["partialFilterExpression"] = "deleted" })
            .assert().containsExactly("its partial filter is none, not {\"version\": {\"\$exists\": true}}")
        sparse.specification.assert().isEqualTo("{\"key\": {\"key\": 1}, \"name\": \"by_key\", \"sparse\": true}")
    }

    @Test
    fun `an option written as zero or as anything but a boolean or a number is not set`() {
        definition.differences(reported { this["unique"] = 0 }).assert().containsExactly("it is not unique")
        definition.differences(reported { this["unique"] = "true" }).assert().containsExactly("it is not unique")
        definition.differences(reported { this["unique"] = false }).assert().containsExactly("it is not unique")
    }

    @Test
    fun `its keys are found under another name, and its specification is what createIndexes takes`() {
        definition.hasKeys(reported { this["name"] = "by_version" }).assert().isTrue()
        definition.hasKeys(Document("key", Document("partition", 1))).assert().isFalse()
        definition.specification.assert().isEqualTo(
            "{\"key\": {\"partition\": 1, \"version\": 1}, \"name\": \"version_key\", \"unique\": true, " +
                "\"partialFilterExpression\": {\"version\": {\"\$exists\": true}}}",
        )
    }
}
