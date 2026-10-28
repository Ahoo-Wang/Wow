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
        "me/ahoo/wow/ops/api/parked/EntryAggregate",
        "me/ahoo/wow/ops/api/parked/EntryRetrySpec",
        "me/ahoo/wow/ops/api/parked/ParkedEntry",
        "me/ahoo/wow/ops/api/parked/ParkedResolution",
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
     * The data classes the ABI dumps may keep, by their JVM names, each with why: closed value types, whose fields are
     * their identity, so a new field would make another type; and the platform's messages.
     */
    val DATA_CLASS_EXCEPTIONS: Map<String, String> = mapOf(
        "me/ahoo/wow/api/aggregate/AggregateType" to "an aggregate's identity: its context and name",
        "me/ahoo/wow/api/aggregate/AggregateRef" to "an aggregate instance's identity: its type, tenant and id",
        "me/ahoo/wow/api/aggregate/Scope" to "the owner and space dimensions of authorization (D56, D189)",
        "me/ahoo/wow/api/decide/Causation" to "the causal link of a command: its correlation and causation ids",
        "me/ahoo/wow/api/subscribe/ExecutionId" to "a subscription's execution: its name, aggregate and version",
        "me/ahoo/wow/spi/log/LogPosition" to "a position in the log: a partition and a sequence",
        "me/ahoo/wow/api/query/QueryField" to "a field path, its identity; generated code builds it (`<State>Properties`)",
    ) + PLATFORM_MESSAGES.associateWith { "a platform context's message type, governed by its Manifest" }

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

    /**
     * The `me/ahoo/wow` types an ABI dump of [MODULES] names in a public signature while no dump declares them, each
     * with the change that removes it. A dump leaves out every `@InternalWowApi` declaration, so such a type is one a
     * third party cannot use without opting in to Wow's internals. A fix removes its entry: an entry no dump still
     * names this way is stale and fails the rule.
     */
    val ABI_CLOSURE_ALLOWED: Map<String, String> = emptyMap()

    private val ABI_REFERENCE = Regex("""me/ahoo/wow/[\w/$]+""")

    private val ABI_SYNTHETIC = Regex("""^\s+public (?:static )?synthetic """)

    /** A class an ABI dump declares, a protected one (a spec's protected companion) included. */
    private val ABI_DECLARED_CLASS = Regex("""^(?:public|protected) [^{]*?\bclass ([\w/$]+)""")

    private fun declaredClass(line: String): String? = ABI_DECLARED_CLASS.find(line)?.groupValues?.get(1)

    /**
     * The public signatures of the ABI dumps of [MODULES] that name a `me/ahoo/wow` type no dump declares, outside
     * [allowed] ([ABI_CLOSURE_ALLOWED]), as `dump: class: type`, and each allowed type no dump names that way any more
     * (a stale entry): the public API is closed when every type it names is public too.
     *
     * Two kinds of line are read apart. A synthetic default-arguments bridge is skipped: it mirrors a declaration the
     * dump lists too, or an internal constructor no other module can call, so it promises nothing of its own. A
     * protected class counts as declared, as a spec's protected companion is: a subclass reaches it.
     */
    fun abiClosureViolations(
        repositoryRoot: File,
        allowed: Map<String, String> = ABI_CLOSURE_ALLOWED,
    ): List<String> {
        val dumps = MODULES.map { "${it.dir}/api/${it.name}.api" to File(repositoryRoot, "${it.dir}/api/${it.name}.api") }
            .filter { (_, dump) -> dump.isFile }
            .map { (path, dump) -> path to dump.readLines() }
        val declared = dumps.flatMap { (_, lines) -> lines.mapNotNull(::declaredClass) }.toSet()
        val leaked = mutableSetOf<String>()
        val violations = dumps.flatMap { (path, lines) ->
            var owner = ""
            lines.flatMap { line ->
                declaredClass(line)?.let { owner = it }
                // A synthetic default-arguments bridge mirrors a declaration the dump also lists, or one no other
                // module can call (an internal constructor's): it promises nothing of its own.
                if (ABI_SYNTHETIC.containsMatchIn(line)) return@flatMap emptyList()
                ABI_REFERENCE.findAll(line).map { it.value }.filter { it !in declared }.map { owner to it }.toList()
            }.distinct().onEach { (_, type) -> leaked += type }
                .filter { (_, type) -> type !in allowed }
                .map { (owner, type) -> "$path: $owner: $type" }
        }
        return violations + (allowed.keys - leaked).map { "stale entry: $it" }
    }

    private val ANY_OPT_IN = Regex("""\b(?:OptIn|optIn)\b""")

    /**
     * The lines of the third-party store (`test/spi-consumer`) that opt in to anything, its tests included: it proves
     * that a backend is written on Wow's public, stable API alone, so neither its build nor any source opts in. A
     * fixture it needs from Wow's internals is the TCK's to give (as `QueryBackendSpec.indexPlan` gives a plan).
     */
    fun spiConsumerOptIns(repositoryRoot: File): List<String> {
        val root = File(repositoryRoot, "test/spi-consumer")
        return root.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && (it.extension == "kt" || it.name == "build.gradle.kts") }
            .sortedBy { it.path }
            .flatMap { file ->
                val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                file.readLines().mapIndexedNotNull { index, line ->
                    if (ANY_OPT_IN.containsMatchIn(line)) "$path:${index + 1}: ${line.trim()}" else null
                }
            }.toList()
    }

    /**
     * The classes of `:spi`'s ABI dump that may keep a public constructor, each with why. Any other final class of the
     * SPI but an exception is a value a provider receives or returns: its constructor is internal to Wow and it is
     * built with `of`, so a field can be added without breaking a backend (D216).
     */
    val SPI_PUBLIC_CONSTRUCTORS: Map<String, String> = mapOf(
        "me/ahoo/wow/spi/log/LogPosition" to "a position is its partition and sequence, a closed value",
        "me/ahoo/wow/spi/schema/EnsuredOnce" to "a helper a store creates for its structures, not a value",
    )

    private val SPI_CLASS = Regex("""^public final class ([\w/$]+)(?: : ([^{]*))?\s*\{""")
    private val SPI_CONSTRUCTOR = Regex("""^\s+public fun <init> """)

    /**
     * The final classes of `:spi`'s ABI dump with a public constructor, but the exceptions and
     * [SPI_PUBLIC_CONSTRUCTORS], and each allowed class that has none any more (a stale entry).
     */
    fun spiPublicConstructors(repositoryRoot: File): List<String> {
        val dump = File(repositoryRoot, "${SPI.dir}/api/${SPI.name}.api").takeIf { it.isFile } ?: return emptyList()
        var owner: String? = null
        val constructed = mutableSetOf<String>()
        dump.readLines().forEach { line ->
            val match = SPI_CLASS.find(line)
            when {
                match != null -> {
                    val exception = match.groupValues[1].endsWith("Exception") || "Exception" in match.groupValues[2]
                    owner = match.groupValues[1].takeUnless { exception }
                }
                line.startsWith("public ") -> owner = null
                SPI_CONSTRUCTOR.containsMatchIn(line) -> owner?.let(constructed::add)
            }
        }
        return (constructed - SPI_PUBLIC_CONSTRUCTORS.keys).sorted() +
            (SPI_PUBLIC_CONSTRUCTORS.keys - constructed).map { "stale entry: $it" }
    }

    private val SPI_DECLARATION =
        Regex("""^(?:public )?(?:(?:fun|sealed|abstract) )*(interface|abstract class|sealed class) (\w+)""")

    /**
     * The public interfaces and abstract classes of `:spi`'s sources that carry neither `@WowSpi` (a port or a value
     * of a port, which a backend opts in to) nor `@InternalWowApi`, as `file:line: declaration`: every extension point
     * of the SPI says what it is.
     */
    fun spiUnmarkedTypes(repositoryRoot: File): List<String> {
        val root = File(repositoryRoot, "${SPI.dir}/src/main/kotlin")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
            val lines = file.readLines()
            lines.mapIndexedNotNull { index, line ->
                if (!SPI_DECLARATION.containsMatchIn(line)) return@mapIndexedNotNull null
                val annotations = lines.subList(0, index).asReversed().takeWhile { it.startsWith("@") }
                val marked = annotations.any { it.startsWith("@WowSpi") || it.startsWith("@InternalWowApi") }
                if (marked) {
                    null
                } else {
                    "${file.relativeTo(
                        repositoryRoot
                    ).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                }
            }
        }.toList()
    }
}
