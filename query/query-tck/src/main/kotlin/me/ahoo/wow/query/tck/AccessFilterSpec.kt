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

package me.ahoo.wow.query.tck

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessOperator
import me.ahoo.wow.api.access.AccessPredicate
import me.ahoo.wow.api.access.AccessPredicate.AllOf
import me.ahoo.wow.api.access.AccessPredicate.Allowed
import me.ahoo.wow.api.access.AccessPredicate.AnyOf
import me.ahoo.wow.api.access.AccessPredicate.Comparison
import me.ahoo.wow.api.access.AccessPredicate.Denied
import me.ahoo.wow.api.access.AccessPredicate.Not
import me.ahoo.wow.api.access.AccessPredicate.Unknown
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SnapshotProperties
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.policy.AccessFilters
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The access filter contract: every snapshot query backend answers an access policy's filter ([AccessFilters]) as the
 * kernel evaluates the policy ([AccessPredicate.allows]), so a policy grants the same aggregates whether a query, a
 * point read or a command asks. A backend's test extends it, stores the records each test gives in [store], and
 * supplies its binding of an aggregate type in [binding]; on the in-memory backend it is the matrix that holds the
 * kernel's evaluator to the point reads', on the others the access attributes' push-down. A snapshot store keeps no
 * record not stamped ([keepsUnstamped]).
 *
 * Each record holds an aggregate's access attributes as its snapshot does: the built-in owner and space as [owner]
 * and [space] (`ownerId` and `spaceId`; an event stream's `access.owner` and `access.space`), each declared attribute
 * under [fieldOf] it, `access.<name>` by default, its value as stored ([accessTree]: a string, an enum by its name, an
 * integer as a long, a boolean, a multiple attribute a sorted array of distinct values), an absent one left out: a
 * record written before the attribute was declared. A record not yet stamped has a null `access`. On a store that keeps
 * such records ([keepsUnstamped]) every filter is guarded by `IS_NOT_NULL` of [access], as the query assembly guards an
 * events query, so a record not stamped is never read, not even by a check of absence; on a snapshot store, whose
 * records are always stamped, there is no guard, as the assembly sends none for snapshots.
 *
 * The matrix covers each operator of one value and of several, negated or not, on present values, on values a record
 * lacks and on a record not stamped, with claims the caller holds and those it does not: a negation over a missing
 * value never grants (a document store's `$nor` matches a missing field, which the guard of each negation excludes).
 */
abstract class AccessFilterSpec {
    /** Stores [records], all of [type], where the backend under test reads them. */
    protected abstract suspend fun store(type: AggregateType, records: List<AccessRecord>)

    /** The query backend of [type]'s snapshots, with its storage adapter. */
    protected abstract fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>

    /** The field of the access attributes, null on a record not stamped: `access`. */
    protected open val access: QueryField<*> = QueryField(ACCESS)

    /** The field the attribute [name] is stored under: `access.<name>`. */
    protected open fun fieldOf(name: String): QueryField<*> = QueryField("${access.path}.$name")

    /**
     * Whether the store keeps a record not yet stamped, one whose `access` is null. A snapshot store does not: a
     * snapshot's attributes are always recorded (`SnapshotRecord.access` is never null, and a table's column is
     * `NOT NULL DEFAULT '{}'`), so it stores the matrix's record not stamped as one stamped with no attributes, and the
     * matrix expects for it what the kernel grants that record.
     */
    protected open val keepsUnstamped: Boolean = true

    /** The field of the built-in owner: a snapshot's `ownerId`. */
    protected open val owner: QueryField<*> = SnapshotProperties.OWNER_ID

    /** The field of the built-in space: a snapshot's `spaceId`. */
    protected open val space: QueryField<*> = SnapshotProperties.SPACE_ID

    /** The aggregate type of this test: a fresh context, so each test has its own records. */
    protected val type: AggregateType =
        AggregateType("tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX), "document")

    /**
     * An aggregate's access attributes: its [id], its [owner] and [space], and the [values] of its declared
     * attributes by name, an absent one left out (a multiple one's a collection, an enum's its constant);
     * [stamped] false for a record holding no attribute at all.
     */
    class AccessRecord(
        val id: String,
        val owner: String? = null,
        val space: String? = null,
        val values: Map<String, Any?> = emptyMap(),
        val stamped: Boolean = true,
    )

    /**
     * [record]'s declared attributes as its snapshot's `access` holds them: an integer a long, a multiple attribute
     * its distinct values sorted; null for a record not stamped.
     */
    protected fun accessTree(record: AccessRecord): ObjectNode? {
        if (!record.stamped) return null
        val tree = WowJson.mapper.createObjectNode()
        record.values.filterValues { it != null }.forEach { (name, value) -> tree.set(name, treeOf(stored(value!!))) }
        return tree
    }

    /**
     * [record] as the snapshot of it a snapshot store writes, in [type]: its owner and space its scope, its declared
     * attributes in their stored form, none on a record not stamped ([keepsUnstamped]).
     */
    protected fun snapshotOf(type: AggregateType, record: AccessRecord): SnapshotRecord = SnapshotRecord(
        ref = type.ref(SNAPSHOT_TENANT, record.id),
        version = 1,
        schemaFingerprint = "tck",
        state = "{}",
        scope = Scope(record.owner, record.space),
        firstEventTime = 0,
        lastEventTime = 0,
        access = AccessValues.of(accessTree(record)).orEmpty(),
    )

    @TestFactory
    fun `access filter matrix`(): List<DynamicTest> {
        val records = if (keepsUnstamped) {
            RECORDS
        } else {
            RECORDS.map { if (it.stamped) it else AccessRecord(it.id, it.owner, it.space, it.values) }
        }
        val schema = runBlocking {
            store(type, records)
            schemaOf(binding(type))
        }
        val backend = binding(type).backend
        return CASES.map { (name, predicate) ->
            DynamicTest.dynamicTest(name) {
                val expected = records.filter { record ->
                    record.stamped && predicate.allows { valueOf(record, it) }
                }.map { it.id }
                val compiled = AccessFilters.compile(predicate, ::fieldOf)
                val filter = if (keepsUnstamped) AndFilter(listOf(IsNotNullFilter(access), compiled)) else compiled
                val actual = runBlocking {
                    backend.list(QueryAdmission.Trusted.list(ListQuery(filter, limit = records.size + 1), schema))
                        .toList().map { it.path("aggregateId").stringValue() }
                }
                actual.toSet().assert().describedAs("$name as $filter").isEqualTo(expected.toSet())
            }
        }
    }

    /** The field of [attribute]: the built-ins' system fields, a declared one's [fieldOf]. */
    private fun fieldOf(attribute: AccessAttribute<*, *>): QueryField<*> = when (attribute) {
        AccessAttribute.OWNER -> owner
        AccessAttribute.SPACE -> space
        else -> fieldOf(attribute.name)
    }

    /** [attribute]'s value in [record], as the kernel derives it: null where the record lacks it. */
    private fun valueOf(record: AccessRecord, attribute: AccessAttribute<*, *>): Any? = when (attribute) {
        AccessAttribute.OWNER -> record.owner
        AccessAttribute.SPACE -> record.space
        else -> record.values[attribute.name]
    }

    private suspend fun schemaOf(binding: QueryBackendBinding<QueryBackend>): QueryModelSchema {
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        return DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, declaration())))),
            binding.storage,
        ).schema()
    }

    /** The declared attributes' fields ([DatasetAccessSpec.ATTRIBUTES]), as the Manifest types them. */
    private fun declaration(): QuerySchemaDeclaration = querySchema {
        DatasetAccessSpec.ATTRIBUTES.forEach { attribute ->
            val type = when (attribute.type) {
                AccessAttributeManifest.Type.STRING -> QueryValueType.STRING
                AccessAttributeManifest.Type.INTEGER -> QueryValueType.INTEGER
                AccessAttributeManifest.Type.BOOLEAN -> QueryValueType.BOOLEAN
            }
            field(fieldOf(attribute.name).path) {
                if (attribute.multiple) items { types(type) } else types(type)
            }
        }
    }

    /** The attributes of the matrix's documents. */
    enum class Level { LOW, HIGH }

    class Document

    /** The matrix's predicates' attributes: those of [DatasetAccessSpec.ATTRIBUTES], typed. */
    private object Attributes {
        val DEPARTMENT = AccessAttribute.single<Document, String>("department", String::class.java)
        val ORG = AccessAttribute.single<Document, String>("org", String::class.java)
        val LEVEL = AccessAttribute.single<Document, Level>("level", Level::class.java)
        val PAGES = AccessAttribute.single<Document, Int>("pages", Int::class.javaObjectType)
        val PUBLISHED = AccessAttribute.single<Document, Boolean>("published", Boolean::class.javaObjectType)
        val SHARED_WITH = AccessAttribute.multiple<Document, String>("sharedWith", String::class.java)
    }

    internal companion object {
        private const val ACCESS = "access"
        private const val SNAPSHOT_TENANT = "tenant"
        private const val CONTEXT_SUFFIX = 12

        /** The matrix's documents, each with the attributes it holds now. */
        val RECORDS = listOf(
            AccessRecord(
                "d1",
                owner = "alice",
                space = "s1",
                values = mapOf(
                    "department" to "sales",
                    "org" to "/acme/sales/east",
                    "level" to Level.HIGH,
                    "pages" to 3,
                    "published" to true,
                    "sharedWith" to setOf("bob", "carol"),
                ),
            ),
            AccessRecord(
                "d2",
                owner = "bob",
                space = "s2",
                values = mapOf(
                    "department" to "hr",
                    "org" to "/acme/hr",
                    "level" to Level.LOW,
                    "pages" to 10,
                    "published" to false,
                    "sharedWith" to setOf("bob"),
                ),
            ),
            // Its single attributes absent, no one shared with.
            AccessRecord("d3", owner = "alice", values = mapOf("sharedWith" to emptySet<String>())),
            AccessRecord(
                "d4",
                space = "s1",
                values = mapOf("department" to "sales", "org" to "/beta", "sharedWith" to setOf("dave"))
            ),
            // Written before the attributes were: its access null.
            AccessRecord("d5", owner = "carol", space = "s2", stamped = false),
            // Written before every attribute but its department was declared: they are missing, the empty set too.
            AccessRecord("d6", owner = "bob", values = mapOf("department" to "hr")),
        )

        private fun eq(attribute: AccessAttribute<*, *>, value: Any) = Comparison(
            attribute,
            AccessOperator.EQUAL,
            listOf(value)
        )

        private fun treeOf(value: Any?): JsonNode = WowJson.mapper.valueToTree(value)

        /** [value] as an access record stores it: an integer a long, several values distinct and sorted. */
        fun stored(value: Any): Any = when (value) {
            is Int -> value.toLong()
            is Collection<*> -> value.map { stored(it!!) }.distinct().sortedBy { it.toString() }
            else -> value
        }

        private val SALES = eq(Attributes.DEPARTMENT, "sales")
        private val SHARED_WITH_BOB = Comparison(Attributes.SHARED_WITH, AccessOperator.CONTAINS, listOf("bob"))

        /** Each predicate of the matrix, by the name of its case. */
        val CASES: List<Pair<String, AccessPredicate>> = listOf(
            "allowed" to Allowed,
            "denied" to Denied,
            "unknown claim" to Unknown,
            "not unknown claim" to Not(Unknown),
            "eq" to SALES,
            "not eq: absent and unstamped never" to Not(SALES),
            "eq enum" to eq(Attributes.LEVEL, Level.HIGH),
            "not eq enum" to Not(eq(Attributes.LEVEL, Level.HIGH)),
            "eq integer" to eq(Attributes.PAGES, 10),
            "eq boolean" to eq(Attributes.PUBLISHED, true),
            "not eq boolean" to Not(eq(Attributes.PUBLISHED, true)),
            "in" to Comparison(Attributes.DEPARTMENT, AccessOperator.IN, listOf("hr", "legal")),
            "not in" to Not(Comparison(Attributes.DEPARTMENT, AccessOperator.IN, listOf("hr", "legal"))),
            "in enum" to Comparison(Attributes.LEVEL, AccessOperator.IN, listOf(Level.LOW, Level.HIGH)),
            "starts with" to Comparison(Attributes.ORG, AccessOperator.STARTS_WITH, listOf("/acme")),
            "not starts with" to Not(Comparison(Attributes.ORG, AccessOperator.STARTS_WITH, listOf("/acme/sales"))),
            "is null: unstamped never" to Comparison(Attributes.DEPARTMENT, AccessOperator.IS_NULL),
            "is null of a missing attribute" to Comparison(Attributes.ORG, AccessOperator.IS_NULL),
            "not eq of a missing attribute never" to Not(eq(Attributes.ORG, "/acme/hr")),
            "not is null" to Not(Comparison(Attributes.DEPARTMENT, AccessOperator.IS_NULL)),
            "contains" to SHARED_WITH_BOB,
            "not contains: missing and unstamped never" to Not(SHARED_WITH_BOB),
            "contains any" to Comparison(Attributes.SHARED_WITH, AccessOperator.CONTAINS_ANY, listOf("carol", "dave")),
            "not contains any" to Not(Comparison(Attributes.SHARED_WITH, AccessOperator.CONTAINS_ANY, listOf("carol", "dave"))),
            "is empty: missing and unstamped never" to Comparison(Attributes.SHARED_WITH, AccessOperator.IS_EMPTY),
            "not is empty" to Not(Comparison(Attributes.SHARED_WITH, AccessOperator.IS_EMPTY)),
            "owner" to eq(AccessAttribute.OWNER, "alice"),
            "not owner: no owner never" to Not(eq(AccessAttribute.OWNER, "alice")),
            "space in" to Comparison(AccessAttribute.SPACE, AccessOperator.IN, listOf("s1")),
            "space is null" to Comparison(AccessAttribute.SPACE, AccessOperator.IS_NULL),
            "all of" to AllOf(listOf(SALES, SHARED_WITH_BOB)),
            "all of none" to AllOf(emptyList()),
            "any of" to AnyOf(listOf(SALES, SHARED_WITH_BOB, Unknown)),
            "any of none" to AnyOf(emptyList()),
            "not all of" to Not(AllOf(listOf(SALES, SHARED_WITH_BOB))),
            "not any of" to Not(AnyOf(listOf(SALES, SHARED_WITH_BOB))),
            "not any of unknown and denied" to Not(AnyOf(listOf(Unknown, Denied))),
            "nested" to AnyOf(listOf(AllOf(listOf(Not(SALES), eq(AccessAttribute.OWNER, "bob"))), Not(Not(SHARED_WITH_BOB)))),
        )
    }
}
