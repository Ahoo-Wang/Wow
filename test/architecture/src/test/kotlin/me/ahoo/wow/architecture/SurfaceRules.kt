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

package me.ahoo.wow.architecture

import me.ahoo.wow.architecture.ArchitectureRules.API
import me.ahoo.wow.architecture.ArchitectureRules.MANIFEST
import me.ahoo.wow.architecture.ArchitectureRules.MIGRATE_DATA
import me.ahoo.wow.architecture.ArchitectureRules.MODULES
import me.ahoo.wow.architecture.ArchitectureRules.QUERY_API
import me.ahoo.wow.architecture.ArchitectureRules.SPI
import java.io.File

/**
 * The rules that keep the public surface what Wow promises: what the ABI dumps (`<module>/api/<module>.api`) may hold,
 * and the modules that keep no dump.
 */
object SurfaceRules {
    /**
     * The modules whose ABI holds no data class (decisions D216, D227, D228): a type users, backends or Wow's tools
     * construct may gain fields within a format (D59), and a data class's `copy` and `componentN` change their
     * signatures when it does. Every other module's dump is held to the same rule through [DATA_CLASS_EXCEPTIONS].
     */
    val PLAIN_CLASS_MODULES: List<WowProject> = listOf(API, SPI, MANIFEST, QUERY_API)

    /**
     * The commands, events and values of the platform contexts' APIs: an application's message types, as an eShop
     * context's are, whose fields their Manifest's compatibility check governs, not the ABI.
     */
    private val PLATFORM_MESSAGES = listOf(
        "me/ahoo/wow/ops/api/parked/ActionRefused",
        "me/ahoo/wow/ops/api/parked/ApplyRetrySpec",
        "me/ahoo/wow/ops/api/parked/ApplyRetrySpecRequested",
        "me/ahoo/wow/ops/api/parked/MarkRecoverable",
        "me/ahoo/wow/ops/api/parked/MarkRecoverableRequested",
        "me/ahoo/wow/ops/api/parked/ParkedRecorded",
        "me/ahoo/wow/ops/api/parked/ParkedResolved",
        "me/ahoo/wow/ops/api/parked/RecordActionRefused",
        "me/ahoo/wow/ops/api/parked/RecordParked",
        "me/ahoo/wow/ops/api/parked/RecordResolved",
        "me/ahoo/wow/ops/api/parked/Resume",
        "me/ahoo/wow/ops/api/parked/ResumeRequested",
        "me/ahoo/wow/ops/api/parked/Skip",
        "me/ahoo/wow/ops/api/parked/SkipRequested",
        "me/ahoo/wow/viewstore/api/SystemView",
        "me/ahoo/wow/viewstore/api/preferences/SetViewPreferences",
        "me/ahoo/wow/viewstore/api/preferences/ViewPreferencesSet",
        "me/ahoo/wow/viewstore/api/view/CreateView",
        "me/ahoo/wow/viewstore/api/view/RenameView",
        "me/ahoo/wow/viewstore/api/view/SaveView",
        "me/ahoo/wow/viewstore/api/view/ViewAudienceChanged",
        "me/ahoo/wow/viewstore/api/view/ViewClaimed",
        "me/ahoo/wow/viewstore/api/view/ViewCreated",
        "me/ahoo/wow/viewstore/api/view/ViewRenamed",
        "me/ahoo/wow/viewstore/api/view/ViewSaved",
        "me/ahoo/wow/viewstore/api/wording/ClearDatasetWording",
        "me/ahoo/wow/viewstore/api/wording/DatasetWordingSet",
        "me/ahoo/wow/viewstore/api/wording/SetDatasetWording",
    )

    /**
     * The data classes the dumps held when the rule widened to every module (finding R5.A-1): each is to become a
     * plain class (D216 ⑤) or to earn an entry of its own with a reason. The list only shrinks: a new data class
     * fails the rule, and an entry the dumps no longer hold is stale.
     */
    private val PLAIN_CLASS_BACKLOG = listOf(
        "me/ahoo/wow/kafka/TopicDurability",
        "me/ahoo/wow/kernel/CommandType",
        "me/ahoo/wow/kernel/HandlerRetry",
        "me/ahoo/wow/kernel/schedule/ScheduleJob",
        "me/ahoo/wow/runtime/admission/Authorization${'$'}Denied",
        "me/ahoo/wow/runtime/admission/CommandAction",
        "me/ahoo/wow/runtime/admission/CommandBody${'$'}Tree",
        "me/ahoo/wow/runtime/admission/CommandBody${'$'}Value",
        "me/ahoo/wow/runtime/admission/CommandInput",
        "me/ahoo/wow/runtime/admission/CommandTarget",
        "me/ahoo/wow/runtime/admission/TargetScope",
        "me/ahoo/wow/runtime/call/OutgoingCall",
        "me/ahoo/wow/runtime/cluster/CommandWire",
        "me/ahoo/wow/runtime/cluster/LeaseGroup",
        "me/ahoo/wow/runtime/cluster/ReplyTo",
        "me/ahoo/wow/runtime/ledger/LedgerPublishing",
        "me/ahoo/wow/runtime/node/SourceTopics",
        "me/ahoo/wow/runtime/snapshot/SnapshotPlacement",
        "me/ahoo/wow/runtime/subscription/ParkedNotice",
        "me/ahoo/wow/runtime/subscription/RetryPolicy",
        "me/ahoo/wow/client/wire/ScopeHeader${'$'}QueryTarget",
        "me/ahoo/wow/http/problem/Problem",
        "me/ahoo/wow/models/common/TenantAwareId",
        "me/ahoo/wow/query/tck/DatasetColumn",
        "me/ahoo/wow/query/tck/DatasetTable",
        "me/ahoo/wow/query/CrossTenant",
        "me/ahoo/wow/query/GroupWindow${'$'}First",
        "me/ahoo/wow/query/PageWindow${'$'}Keyset",
        "me/ahoo/wow/query/PageWindow${'$'}Offset",
        "me/ahoo/wow/query/QueryBackendBinding",
        "me/ahoo/wow/query/QueryBindingError",
        "me/ahoo/wow/query/QueryCaller",
        "me/ahoo/wow/query/QueryEntryPolicy",
        "me/ahoo/wow/query/QueryScope",
        "me/ahoo/wow/query/dataset/QueryDataset",
        "me/ahoo/wow/query/dataset/QueryDatasetChange${'$'}Added",
        "me/ahoo/wow/query/dataset/QueryDatasetChange${'$'}Removed",
        "me/ahoo/wow/query/dataset/QueryDatasetChange${'$'}Replaced",
        "me/ahoo/wow/query/dataset/QueryDatasetScope",
        "me/ahoo/wow/query/filter/QueryContext",
        "me/ahoo/wow/query/index/QueryIndexDeclaration",
        "me/ahoo/wow/query/schema/DeclarationValue${'$'}Set",
        "me/ahoo/wow/query/schema/QueryFieldDeclaration",
        "me/ahoo/wow/query/schema/QueryMask${'$'}Keep",
        "me/ahoo/wow/query/schema/QueryMask${'$'}Strategy",
        "me/ahoo/wow/query/schema/QueryModel",
        "me/ahoo/wow/query/schema/QuerySchemaDeclaration",
        "me/ahoo/wow/query/schema/QueryValueType",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ArrayEquality",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}CountRequiresFilter",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}CursorNotAllowed",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}CursorSortDuplicate",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}CursorSortTooMany",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}DuplicateSortField",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ElementScopeRequired",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}EventProjectionTypeRequired",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ExactCountDisabled",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ExpensiveOperatorDisabled",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}FilterTooLarge",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}FirstLastRequiresOrderBy",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}FirstLastRequiresSingleValue",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}IdentityUndefined",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}MetricFilterArrayField",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}MissingKeyRequiresString",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}NotCollection",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}NotProjectable",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}NotSingleString",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ParallelArraySort",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}PreviousUnshiftable",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ProtectedAggregation",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ProtectedComparison",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ResidualGroupsExceeded",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}SizeOutOfRange",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}SortTooMany",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}StorageUnsupported",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}TemporalAggregationUnsupported",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}TemporalConfigurationConflict",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}TemporalRepresentationRequired",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}UnknownField",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}UnsupportedCapability",
        "me/ahoo/wow/query/schema/QueryViolation${'$'}ValueMismatch",
        "me/ahoo/wow/query/schema/Temporal${'$'}Epoch",
        "me/ahoo/wow/query/schema/Temporal${'$'}Formatted",
    )

    /**
     * The data classes the ABI dumps may keep, by their JVM names, each with why: closed value types, whose fields are
     * their identity, so a new field would make another type; the platform's messages; the backlog.
     */
    val DATA_CLASS_EXCEPTIONS: Map<String, String> = mapOf(
        "me/ahoo/wow/api/aggregate/AggregateType" to "an aggregate's identity: its context and name",
        "me/ahoo/wow/api/aggregate/AggregateRef" to "an aggregate instance's identity: its type, tenant and id",
        "me/ahoo/wow/api/aggregate/Scope" to "the owner and space dimensions of authorization (D56, D189)",
        "me/ahoo/wow/api/decide/Causation" to "the causal link of a command: its correlation and causation ids",
        "me/ahoo/wow/api/subscribe/ExecutionId" to "a subscription's execution: its name, aggregate and version",
        "me/ahoo/wow/spi/log/LogPosition" to "a position in the log: a partition and a sequence",
        "me/ahoo/wow/spi/transport/PartitionOffsets" to "a range of offsets: its start and end",
        "me/ahoo/wow/api/query/QueryField" to "a field path, its identity; generated code builds it (`<State>Properties`)",
    ) + PLATFORM_MESSAGES.associateWith { "a platform context's message type, governed by its Manifest" } +
        PLAIN_CLASS_BACKLOG.associateWith { "known when the rule widened: to become a plain class" }

    private val ABI_CLASS = Regex("""^public (?:final |abstract |open |sealed )*class ([\w/$]+)""")
    private val DATA_CLASS_MEMBER = Regex("""\bfun (?:copy|copy\${'$'}default|component\d+) \(""")

    /**
     * The `copy` and `componentN` members of every module's ABI dump outside [DATA_CLASS_EXCEPTIONS],
     * as `dump: class: member`, and each exception the dumps no longer have a data class for (a stale entry).
     */
    fun dataClassMembers(repositoryRoot: File): List<String> {
        val dataClasses = mutableSetOf<String>()
        val violations = MODULES.flatMap { module ->
            val dump = File(repositoryRoot, "${module.dir}/api/${module.name}.api")
            if (!dump.isFile) return@flatMap emptyList()
            var owner = ""
            dump.readLines().mapNotNull { line ->
                ABI_CLASS.find(line)?.let { owner = it.groupValues[1] }
                if (DATA_CLASS_MEMBER.containsMatchIn(line)) {
                    dataClasses += owner
                    if (owner in DATA_CLASS_EXCEPTIONS) null else "${module.dir}/api/${module.name}.api: $owner: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        return violations + (DATA_CLASS_EXCEPTIONS.keys - dataClasses).map { "stale exception: $it" }
    }

    private val ABI_ANY_CLASS = Regex("""^public [^{]*?\bclass ([\w/$]+)""")

    /**
     * The nested classes the ABI dumps of [MODULES] keep while their outer class is left out (finding R5.A-7): an
     * `@InternalWowApi` outer class does not hide its nested classes or its companion, which then freeze into the
     * dump on their own, so each one is marked too. As `dump: Outer$Nested`.
     */
    fun hiddenOuterViolations(repositoryRoot: File): List<String> = MODULES.flatMap { module ->
        val dump = File(repositoryRoot, "${module.dir}/api/${module.name}.api")
        if (!dump.isFile) return@flatMap emptyList()
        val classes = dump.readLines().mapNotNull { ABI_ANY_CLASS.find(it)?.groupValues?.get(1) }.toSet()
        classes.filter { '$' in it && it.substringBeforeLast('$') !in classes }.sorted()
            .map { "${module.dir}/api/${module.name}.api: $it" }
    }

    private val TOP_LEVEL_DECLARATION =
        Regex(
            """^(?:(?:data|sealed|enum|abstract|open|inline|suspend|const|value|annotation|fun) )*(?:class|interface|object|fun|val|var|typealias)\b"""
        )

    /**
     * The top-level declarations of `:migrate-data` that are not `internal` or `private`, but its entry point `main`
     * (finding R5.C-6): the tool is published to run, its contract is its arguments and configuration, so none of its
     * JVM signatures is a promise. As `file:line: declaration`.
     */
    fun migrateDataPublicDeclarations(repositoryRoot: File): List<String> {
        val root = File(repositoryRoot, "${MIGRATE_DATA.dir}/src/main/kotlin")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (TOP_LEVEL_DECLARATION.containsMatchIn(line) && !line.startsWith("fun main(")) {
                    "${file.relativeTo(repositoryRoot).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }.toList()
    }
}
