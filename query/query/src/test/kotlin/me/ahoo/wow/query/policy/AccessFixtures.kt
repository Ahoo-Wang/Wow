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

package me.ahoo.wow.query.policy

import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.JsonNode

/**
 * A service of three aggregates for the access policies' tests: documents, which declare access attributes and read by
 * their policy; notes, which declare none and read by the default rule; and ledgers, which belong to their tenant.
 */
internal object AccessFixtures {
    val DOCUMENT = AggregateType("docs", "document")
    val NOTE = AggregateType("docs", "note")
    val LEDGER = AggregateType("docs", "ledger")

    class DocumentState

    class NoteState

    class LedgerState

    val DEPARTMENT: AccessAttribute.Single<DocumentState, String> =
        AccessAttribute.single("department", String::class.java)
    val SHARED_WITH: AccessAttribute.Multiple<DocumentState, String> =
        AccessAttribute.multiple("sharedWith", String::class.java)
    val LEVEL: AccessAttribute.Single<DocumentState, Int> = AccessAttribute.single("level", Int::class.javaObjectType)

    /** An attribute documents do not declare: a policy built against another version of their state. */
    val REGION: AccessAttribute.Single<DocumentState, String> = AccessAttribute.single("region", String::class.java)

    val MANIFEST = Manifest(
        contexts = listOf(
            ContextManifest(
                "docs",
                aggregates = listOf(
                    aggregate(
                        DOCUMENT.name,
                        access = listOf(
                            AccessAttributeManifest("department", AccessAttributeManifest.Type.STRING),
                            AccessAttributeManifest("sharedWith", AccessAttributeManifest.Type.STRING, multiple = true),
                            AccessAttributeManifest("level", AccessAttributeManifest.Type.INTEGER),
                        ),
                    ),
                    aggregate(NOTE.name),
                    aggregate(LEDGER.name, ownership = Ownership.TENANT),
                ),
            ),
        ),
        // Each state's schema: a title.
        defs = listOf(DOCUMENT, NOTE, LEDGER).associate { type ->
            type.name to mapOf("type" to "object", "properties" to mapOf("title" to mapOf("type" to "string")))
        },
    )

    /** A document is read by its department's members, those it is shared with, and auditors. */
    val documents: AccessPolicy<DocumentState> = accessPolicy {
        read { caller ->
            anyOf(
                DEPARTMENT isIn caller.claims("departments"),
                SHARED_WITH contains caller.subject,
                allowIf(caller.hasRole("auditor")),
            )
        }
        write { denyAll() }
    }

    fun aggregatesOf(stateType: Class<*>): List<AggregateType> = when (stateType) {
        DocumentState::class.java -> listOf(DOCUMENT)
        NoteState::class.java -> listOf(NOTE)
        LedgerState::class.java -> listOf(LEDGER)
        else -> emptyList()
    }

    fun policies(vararg policies: AccessPolicy<*>): AccessPolicies =
        AccessPolicies.of(MANIFEST, setOf("docs"), policies.toList(), ::aggregatesOf)

    fun caller(
        subject: String?,
        vararg claims: Pair<String, List<String>>,
        scope: Scope = Scope(subject),
        tenantId: String = "acme",
    ): QueryCaller = QueryCaller(Caller.of(tenantId, scope, subject, attributes = claims.toMap()))

    fun record(
        type: AggregateType,
        id: String,
        access: Map<String, Any?> = emptyMap(),
        owner: String = "zed",
        tenantId: String = "acme",
        space: String? = null,
    ) = SnapshotRecord(
        ref = type.ref(tenantId, id),
        version = 1,
        schemaFingerprint = "test",
        state = """{"title":"$id"}""",
        scope = Scope(owner, space),
        firstEventTime = 1,
        lastEventTime = 1,
        access = access,
    )

    /**
     * A stream of [type]'s aggregate [id] committed in [committed], stamped with [access] (null: not stamped yet) and
     * its current owner and space.
     */
    fun stream(
        type: AggregateType,
        id: String,
        access: Map<String, Any?>?,
        owner: String = "zed",
        space: String? = null,
        committed: Scope = Scope(owner, space),
        tenantId: String = "acme",
    ) = EventStreamRecord(
        ref = type.ref(tenantId, id),
        version = 1,
        commandId = "c-$id",
        requestId = "r-$id",
        commandType = "docs.${type.name}.Write",
        commitTime = 1,
        events = listOf(EventRecord("docs.${type.name}.Written", 1, "{}")),
        scope = committed,
        access = access?.let { AccessValues.ofStream(it, Scope(owner, space)) },
    )

    fun state(record: SnapshotRecord): Snapshot<JsonNode> = Snapshot.of(
        ref = record.ref,
        version = record.version,
        scope = record.scope,
        deleted = record.deleted,
        access = record.access,
        operator = null,
        firstEventTime = record.firstEventTime,
        lastEventTime = record.lastEventTime,
        state = WowJson.mapper.readTree(record.state),
    )

    private fun aggregate(
        name: String,
        ownership: Ownership = Ownership.OWNER,
        access: List<AccessAttributeManifest> = emptyList(),
    ) = AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef(name)),
        queries = listOf(QueryManifest(QueryRoutes.SNAPSHOT), QueryManifest(QueryRoutes.EVENTS)),
        ownership = ownership,
        access = access,
    )
}
