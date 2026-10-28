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

package me.ahoo.wow.datasetregistry.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.datasetregistry.api.DatasetDrafted
import me.ahoo.wow.datasetregistry.api.DatasetPublished
import me.ahoo.wow.datasetregistry.api.DatasetRegistryErrorCodes
import me.ahoo.wow.datasetregistry.api.DatasetRetired
import me.ahoo.wow.datasetregistry.api.DatasetRevised
import me.ahoo.wow.datasetregistry.api.DraftDataset
import me.ahoo.wow.datasetregistry.api.FieldSensitivity
import me.ahoo.wow.datasetregistry.api.PublishDataset
import me.ahoo.wow.datasetregistry.api.RetireDataset
import me.ahoo.wow.datasetregistry.api.ReviewSensitivity
import me.ahoo.wow.datasetregistry.api.ReviseDataset
import me.ahoo.wow.datasetregistry.api.SensitivityReviewed
import me.ahoo.wow.datasetregistry.api.SkippedColumn
import me.ahoo.wow.serialization.toObjectNode
import me.ahoo.wow.test.AggregateSpec
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

private const val ID = "analytics.orders"

private fun document(tenant: Boolean = true, cityType: String = "STRING"): ObjectNode = """
    {
      "title": "Orders",
      "identity": "id",
      "scope": { ${if (tenant) "\"tenant\": \"tenant_id\"" else ""} },
      "storage": { "table": "bi.orders" },
      "fields": {
        "id": { "types": ["string"], "nullable": false },
        "tenant_id": { "types": ["string"], "nullable": false },
        "city": { "types": ["$cityType"], "nullable": false },
        "amount": { "types": ["decimal"], "nullable": false },
        "depts": { "kind": "array", "items": { "types": ["string"] }, "nullable": false }
      }
    }
""".trimIndent().toObjectNode()

private val NOT_SENSITIVE = FieldSensitivity()
private val PHONE_LIKE = FieldSensitivity(SensitivityLevel.DISPLAY, keepSuffix = 4)

/** Every string field reviewed: `city` as sensitive, the others as not. */
private val ALL_REVIEWED = mapOf(
    "id" to NOT_SENSITIVE,
    "tenant_id" to NOT_SENSITIVE,
    "city" to PHONE_LIKE,
    "depts" to NOT_SENSITIVE,
)

private val INSPECTOR = DatasetTableInspector { table, _ ->
    if (table == "bi.missing") {
        Mono.error(DatasetRegistryException.invalid("Table [bi.missing] does not exist."))
    } else {
        Mono.just(DatasetDraft(document(tenant = false), listOf(SkippedColumn("attrs", "Map(String, String)"))))
    }
}
private val ACCEPTING = DatasetPublicationCheck { Mono.empty() }

private fun drafted(document: ObjectNode = document()) = DatasetDrafted("bi.orders", document)

class DatasetSpec : AggregateSpec<Dataset, DatasetState>({
    on(aggregateId = ID) {
        inject {
            register(INSPECTOR)
            register(ACCEPTING)
        }
        givenEvent(arrayOf(drafted(), SensitivityReviewed(ALL_REVIEWED))) {
            whenCommand(PublishDataset(ID)) {
                expectNoError()
                expectEventType(DatasetPublished::class)
                expectEventBody<DatasetPublished> {
                    version.assert().isEqualTo(1)
                    document.at("/fields/city/sensitive/level").stringValue().assert().isEqualTo("DISPLAY")
                    document.at("/fields/city/sensitive/keepSuffix").intValue().assert().isEqualTo(4)
                    document.at("/fields/id/sensitive").isMissingNode.assert().isTrue()
                }
                expectState {
                    publishedVersion.assert().isEqualTo(1)
                    published!!.at("/fields/city/sensitive/level").stringValue().assert().isEqualTo("DISPLAY")
                }
                fork("publish again") {
                    whenCommand(PublishDataset(ID)) {
                        expectEventBody<DatasetPublished> { version.assert().isEqualTo(2) }
                    }
                }
                fork("retire") {
                    whenCommand(RetireDataset(ID)) {
                        expectEventBody<DatasetRetired> { version.assert().isEqualTo(1) }
                        expectState {
                            published.assert().isNull()
                            publishedVersion.assert().isEqualTo(1)
                        }
                    }
                }
                fork("revise a field, dropping its review") {
                    whenCommand(ReviseDataset(ID, document(cityType = "INTEGER"))) {
                        expectEventBody<DatasetRevised> {
                            reviews.keys.assert().containsExactlyInAnyOrder("id", "tenant_id", "depts")
                        }
                        expectState {
                            reviews.keys.assert().doesNotContain("city")
                            published.assert().isNotNull()
                        }
                    }
                }
            }
        }
    }
    on(aggregateId = ID) {
        inject {
            register(INSPECTOR)
            register(ACCEPTING)
        }
        givenEvent(arrayOf(drafted(), SensitivityReviewed(mapOf("id" to NOT_SENSITIVE)))) {
            whenCommand(PublishDataset(ID)) {
                expectError<DatasetRegistryException> {
                    errorCode.assert().isEqualTo(DatasetRegistryErrorCodes.DATASET_NOT_PUBLISHABLE)
                    errorMsg.assert().contains("[city, depts, tenant_id]")
                }
            }
        }
    }
    on(aggregateId = ID) {
        inject {
            register(INSPECTOR)
            register(ACCEPTING)
        }
        givenEvent(arrayOf(drafted(document(tenant = false)), SensitivityReviewed(ALL_REVIEWED))) {
            whenCommand(PublishDataset(ID)) {
                expectError<DatasetRegistryException> {
                    errorCode.assert().isEqualTo(DatasetRegistryErrorCodes.DATASET_NOT_PUBLISHABLE)
                    errorMsg.assert().contains("tenant")
                }
            }
        }
    }
    on(aggregateId = ID) {
        inject {
            register(INSPECTOR)
            register(DatasetPublicationCheck { Mono.error(DatasetRegistryException.nameTaken("Taken.")) })
        }
        givenEvent(arrayOf(drafted(), SensitivityReviewed(ALL_REVIEWED))) {
            whenCommand(PublishDataset(ID)) {
                expectError<DatasetRegistryException> {
                    errorCode.assert().isEqualTo(DatasetRegistryErrorCodes.DATASET_NAME_TAKEN)
                }
            }
        }
    }
    on(aggregateId = ID) {
        inject {
            register(INSPECTOR)
            register(ACCEPTING)
        }
        whenCommand(DraftDataset(ID, " bi.orders ")) {
            expectNoError()
            expectEventType(DatasetDrafted::class)
            expectState {
                table.assert().isEqualTo("bi.orders")
                document.assert().isEqualTo(document(tenant = false))
                skipped.assert().containsExactly(SkippedColumn("attrs", "Map(String, String)"))
                published.assert().isNull()
            }
            ref("drafted")
            fork("revise without the tenant") {
                whenCommand(ReviseDataset(ID, document(tenant = false))) {
                    expectError<DatasetRegistryException> {
                        errorCode.assert().isEqualTo(DatasetRegistryErrorCodes.DATASET_INVALID)
                    }
                }
            }
            fork("revise with a sensitive key") {
                val sensitive = document().apply {
                    (at("/fields/city") as ObjectNode).putObject("sensitive").put("level", "DISPLAY")
                }
                whenCommand(ReviseDataset(ID, sensitive)) {
                    expectError<DatasetRegistryException> {
                        errorMsg.assert().contains("review it instead")
                    }
                }
            }
            fork("review a field the draft does not declare") {
                whenCommand(ReviewSensitivity(ID, mapOf("phone" to NOT_SENSITIVE))) {
                    expectError<DatasetRegistryException> { errorMsg.assert().contains("[phone]") }
                }
            }
            fork("review nothing") {
                whenCommand(ReviewSensitivity(ID, emptyMap())) {
                    expectError<DatasetRegistryException> { errorMsg.assert().contains("at least one field") }
                }
            }
            fork("review a negative mask") {
                whenCommand(ReviewSensitivity(ID, mapOf("city" to FieldSensitivity(SensitivityLevel.DISPLAY, -1)))) {
                    expectError<DatasetRegistryException> { errorMsg.assert().contains("negative") }
                }
            }
            fork("retire a dataset never published") {
                whenCommand(RetireDataset(ID)) {
                    expectError<DatasetRegistryException> { errorMsg.assert().contains("not published") }
                }
            }
            fork("revise") {
                whenCommand(ReviseDataset(ID, document())) {
                    expectNoError()
                    expectEventType(DatasetRevised::class)
                    expectState { document.assert().isEqualTo(document()) }
                }
            }
        }
    }
    on(aggregateId = ID) {
        inject {
            register(INSPECTOR)
            register(ACCEPTING)
        }
        whenCommand(DraftDataset(ID, "bi.missing")) {
            expectError<DatasetRegistryException> { errorMsg.assert().contains("does not exist") }
        }
    }
    on(aggregateId = "orders") {
        inject {
            register(INSPECTOR)
            register(ACCEPTING)
        }
        whenCommand(DraftDataset("orders", "bi.orders")) {
            expectError<DatasetRegistryException> { errorMsg.assert().contains("{context}.{dataset}") }
        }
    }
    on(aggregateId = ID) {
        inject {
            register(INSPECTOR)
            register(ACCEPTING)
        }
        whenCommand(DraftDataset(ID, "  ")) {
            expectError<DatasetRegistryException> { errorMsg.assert().contains("needs a table") }
        }
    }
})
