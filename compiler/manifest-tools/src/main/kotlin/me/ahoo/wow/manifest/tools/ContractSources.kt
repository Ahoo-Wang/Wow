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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.QueryFieldNames

/**
 * The Kotlin sources generated from an api module's contract: per context,
 * `<package>.<Context>Contract`, so no consumer of the api module writes a context's name, an aggregate type or a query
 * field's path by hand.
 *
 * - The package is the context's contract package, `wow { contractPackage }` of its domain module, which the domain
 *   module's build requires ([ContractPackages]); the name is the context's name in Pascal case ([pascal]) followed by
 *   `Contract`: `eshop-ordering` is `EshopOrderingContract`. A segment of a package or of a qualified name that is a
 *   Kotlin keyword is quoted ([KotlinNames]).
 * - The object holds the context's `NAME` and, per aggregate, a nested object named after the aggregate in Pascal case
 *   ([aggregateObject]: `Aggregate` follows a name the context's object declares itself, so an aggregate `wait` is
 *   `WaitAggregate`, as the TypeScript contract names it) with its `CONTEXT`, `NAME`, `QUALIFIED_NAME`
 *   (`context.aggregate`, for `@OnEvent(aggregates)`), `TYPE`
 *   (`AggregateType`), `OWNERSHIP`, `ID_SOURCE` (where its id comes from: an `OWNER` aggregate's is its owner's, so a
 *   command sent without an id goes to the caller's own), `SNAPSHOT` (whether it keeps snapshots), `QUERY_MODELS` (the
 *   models a peer queries), `ref(tenantId, id)`, `unassigned(tenantId)` and a `Commands` object holding each command's full type
 *   name ([commandName]: `SubmitOrder` is `SUBMIT_ORDER`), the names a yaml file or a test refers to. The TypeScript
 *   generator's `<Aggregate>Aggregate` constant carries `CONTEXT`, `NAME`, `OWNERSHIP`, `ID_SOURCE` and `SNAPSHOT`
 *   under the same names in camel case (`context`, `name`, `ownership`, `idSource`, `snapshot`), with the aggregate's
 *   exact numbers and its error codes beside them (#7060).
 * - With [queryFields] (`wow-query-api` on the api module's class path, as the compiler requires for its own), each
 *   aggregate object extends `SnapshotProperties`, the system fields of a snapshot, and holds a `QueryField` per path
 *   of its state schema ([StateFields]), named by [QueryFieldNames] exactly as the compiler names the domain module's
 *   `<State>Properties`. A state the contract withholds (`publishState = false`) has none.
 *
 * - The context's rejection codes are `const val`s of an `Errors` object: those of an aggregate
 *   (`context.aggregate.code`) in the aggregate's object, named after the local name ([errorName]: `invalid-item` is
 *   `INVALID_ITEM`), the others (`context.code`, codes the context shares) in the context object's, named after what
 *   follows the context. Each holds the full code: `EshopBasketContract.Basket.Errors.INVALID_ITEM` is
 *   `"eshop-basket.basket.invalid-item"`, what a peer compares a rejection's code with. The TypeScript generator's
 *   `<Context>Contract` nests the same codes alike.
 *
 * - The context's wait hops are the `@JvmField`s of a `Wait` object: `ACCEPTED`, `COMMITTED`, `SNAPSHOT` and
 *   `QUERYABLE` of the context, and per subscription its contract lists that a wait chain can follow
 *   (`SubscriptionManifest.isWaitHop`) the hop of it handling the commit, named
 *   after it ([waitName]: `OrderProcessSaga.onOrderSubmitted` is `ORDER_PROCESS_SAGA__ON_ORDER_SUBMITTED`), what a
 *   caller chains in a `WaitPlan` without writing a subscription's name. Two names of one constant fail the generation.
 *   The TypeScript generator's `<Context>Contract.Wait` builds the same hops.
 *
 * Constants are `const val` or `@JvmField` and `ref` is `@JvmStatic`, so Java reads them as static members
 * (`EshopOrderingContract.Order.TYPE`). The views of the states are classes of their own beside it
 * ([StateViewSources]).
 *
 * The sources import nothing and name everything they do not declare themselves by its qualified name, Wow's types as
 * Kotlin's (`me.ahoo.wow.api.aggregate.Ownership`, `kotlin.String`): an aggregate object nested in the contract (an
 * aggregate `ownership` is an object `Ownership`), a copied class nested in a view, or a domain class of the package
 * hides only a simple name. A declaration named as a root package (`kotlin`, `me`, `java`) still hides it.
 */
internal object ContractSources {
    /** One generated file: its [path] under the source root and its [text]. */
    data class Source(val path: String, val text: String)

    /**
     * The sources of [contract]: per context its `<Context>Contract`, in its package among [contractPackages] (by
     * context name), and the views of its states ([StateViewSources]), which reference a class [onClassPath] holds;
     * with [queryFields], the query fields too.
     */
    fun of(
        contract: Manifest,
        contractPackages: Map<String, String>,
        queryFields: Boolean,
        onClassPath: (String) -> Boolean,
    ): List<Source> {
        val views = StateViewSources(contract.defs, contractPackages, onClassPath)
        return contract.contexts.flatMap { context ->
            val source = of(context, contract.defs, contractPackages, queryFields, onClassPath)
            listOf(source) + views.of(context)
        }
    }

    /**
     * The binary names of the views [of] generates of [contract]'s states, sorted: what the contract jar lists at
     * [PeerContracts.STATE_VIEWS_RESOURCE].
     */
    fun viewsOf(contract: Manifest, contractPackages: Map<String, String>): List<String> {
        val views = StateViewSources(contract.defs, contractPackages) { false }
        return contract.contexts.flatMap(views::namesOf).sorted()
    }

    fun of(
        context: ContextManifest,
        defs: Map<String, Any?>,
        contractPackages: Map<String, String>,
        queryFields: Boolean,
        onClassPath: (String) -> Boolean = { false },
    ): Source {
        val views = StateViewSources(defs, contractPackages, onClassPath)
        val pkg = PeerContracts.contractPackageOf(context, contractPackages)
        val name = "${pascal(context.name)}Contract"
        val text = buildString {
            appendLine("// Generated by the Wow Gradle plugin from this api module's contract; do not edit.")
            appendLine("package ${KotlinNames.qualified(pkg)}")
            appendLine()
            appendLine("/**")
            appendLine(" * The context `${comment(context.name)}`, from its peer contract.")
            context.description?.let { appendLine(" * ${comment(it)}") }
            appendLine(" */")
            appendLine(GENERATED)
            appendLine("public object $name {")
            appendLine("    /** The context's name. */")
            appendLine("    public const val NAME: kotlin.String = ${literal(context.name)}")
            val byAggregate = context.aggregates.associateWith { aggregate ->
                context.errors.filter { aggregateCodeOf(context.name, aggregate.name, it.code) != null }
            }
            val shared = context.errors - byAggregate.values.flatten().toSet()
            if (shared.isNotEmpty()) {
                appendLine()
                append(
                    errors(
                        "    ",
                        "The rejection codes the context shares.",
                        shared
                    ) { it.removePrefix("${context.name}.") }
                )
            }
            appendLine()
            append(wait(name, context))
            context.aggregates.forEach { aggregate ->
                appendLine()
                append(
                    aggregate(
                        name,
                        aggregate,
                        defs,
                        queryFields,
                        context,
                        byAggregate.getValue(aggregate),
                        views,
                    ),
                )
            }
            appendLine("}")
        }
        return Source("${pkg.replace('.', '/')}/$name.kt", text)
    }

    /** The object of [aggregate] within the context object [owner]. */
    private fun aggregate(
        owner: String,
        aggregate: AggregateManifest,
        defs: Map<String, Any?>,
        queryFields: Boolean,
        context: ContextManifest,
        errors: List<ErrorManifest>,
        views: StateViewSources,
    ): String = buildString {
        appendLine("    /**")
        appendLine("     * The aggregate `${comment(aggregate.name)}`.")
        aggregate.description?.let { appendLine("     * ${comment(it)}") }
        appendLine("     */")
        val supertype = if (queryFields) " : $SNAPSHOT_PROPERTIES()" else ""
        appendLine("    $GENERATED")
        appendLine("    public object ${aggregateObject(aggregate.name)}$supertype {")
        appendLine("        /** The name of the aggregate's context. */")
        appendLine("        public const val CONTEXT: kotlin.String = $owner.NAME")
        appendLine()
        appendLine("        /** The aggregate's name within its context. */")
        appendLine("        public const val NAME: kotlin.String = ${literal(aggregate.name)}")
        appendLine()
        appendLine("        /** `context.aggregate`, as `@OnEvent(aggregates)` names the aggregate. */")
        appendLine(
            "        public const val QUALIFIED_NAME: kotlin.String = ${literal("${context.name}.${aggregate.name}")}"
        )
        appendLine()
        appendLine("        /** The aggregate's type. */")
        appendLine("        @kotlin.jvm.JvmField")
        appendLine("        public val TYPE: $AGGREGATE_TYPE = $AGGREGATE_TYPE(CONTEXT, NAME)")
        appendLine()
        appendLine("        /** Who the aggregate belongs to besides its tenant. */")
        appendLine("        @kotlin.jvm.JvmField")
        appendLine("        public val OWNERSHIP: $OWNERSHIP = $OWNERSHIP.${aggregate.ownership.name}")
        appendLine()
        appendLine("        /** Where the aggregate's id comes from; an `OWNER` aggregate's is its owner's. */")
        appendLine("        @kotlin.jvm.JvmField")
        appendLine("        public val ID_SOURCE: $ID_SOURCE = $ID_SOURCE.${aggregate.idSource.name}")
        appendLine()
        appendLine("        /** Whether the aggregate keeps snapshots. */")
        appendLine("        public const val SNAPSHOT: kotlin.Boolean = ${aggregate.snapshot.enabled}")
        appendLine()
        appendLine("        /** The query models of the aggregate a peer reads (`snapshot`, `events`). */")
        appendLine("        @kotlin.jvm.JvmField")
        val models = aggregate.queries.joinToString(", ") { literal(it.model) }
        appendLine(
            "        public val QUERY_MODELS: kotlin.collections.List<kotlin.String> = kotlin.collections.listOf($models)"
        )
        appendLine()
        append(REFS)
        append(commands(context.name, aggregate))
        if (errors.isNotEmpty()) {
            appendLine()
            append(
                errors("        ", "The aggregate's rejection codes, each the full code.", errors) {
                    aggregateCodeOf(context.name, aggregate.name, it)!!
                }
            )
        }
        if (queryFields) {
            append(queryFields(context, aggregate, defs, views))
        }
        appendLine("    }")
    }

    /** The query field of each path of [aggregate]'s state, typed as its view types the path's value. */
    private fun queryFields(
        context: ContextManifest,
        aggregate: AggregateManifest,
        defs: Map<String, Any?>,
        views: StateViewSources,
    ): String = buildString {
        StateFields.of(aggregate.state.schema, defs) { schema, field ->
            views.typeOf(context, aggregate, schema, field)
        }.forEach { (path, type) ->
            appendLine()
            appendLine("        /** `$path` */")
            appendLine("        @kotlin.jvm.JvmField")
            appendLine(
                "        public val ${QueryFieldNames.constantOf(path)}: $QUERY_FIELD<$type> = " +
                    "$QUERY_FIELD.of(${literal(path)})",
            )
        }
    }

    /** Wow's types a `<Context>Contract` names, by their qualified names: it imports nothing. */
    /**
     * The mark of generated code, `@WowGenerated` (`wow-annotations`), which coverage tools exclude it by: on the
     * context object and on each object nested in it, since an annotation marks the class file it is on alone. The
     * state views carry it too ([StateViewSources]).
     */
    const val GENERATED = "@me.ahoo.wow.api.annotation.WowGenerated"
    private const val AGGREGATE_TYPE = "me.ahoo.wow.api.aggregate.AggregateType"
    private const val AGGREGATE_REF = "me.ahoo.wow.api.aggregate.AggregateRef"
    private const val OWNERSHIP = "me.ahoo.wow.api.aggregate.Ownership"
    private const val ID_SOURCE = "me.ahoo.wow.api.aggregate.AggregateIdSource"
    private const val QUERY_FIELD = "me.ahoo.wow.api.query.QueryField"
    private const val SNAPSHOT_PROPERTIES = "me.ahoo.wow.api.query.SnapshotProperties"
    private const val HOP = "me.ahoo.wow.api.result.Hop"

    /** The functions of an aggregate object that refer to one of its instances. */
    private val REFS: String = buildString {
        appendLine("        /** Refers to the instance [id] of the aggregate under [tenantId]. */")
        appendLine("        @kotlin.jvm.JvmStatic")
        appendLine(
            "        public fun ref(tenantId: kotlin.String, id: kotlin.String): $AGGREGATE_REF = TYPE.ref(tenantId, id)"
        )
        appendLine()
        appendLine(
            "        /** Refers to an aggregate under [tenantId] whose id is not assigned yet: one a command creates. */"
        )
        appendLine("        @kotlin.jvm.JvmStatic")
        appendLine("        public fun unassigned(tenantId: kotlin.String): $AGGREGATE_REF = TYPE.unassigned(tenantId)")
    }

    /** The object `Commands` of [aggregate], a `const val` per command it decides; nothing when it decides none. */
    private fun commands(context: String, aggregate: AggregateManifest): String = buildString {
        val commands = aggregate.domainCommands.filterNot { it.mounted }
        if (commands.isNotEmpty()) {
            appendLine()
            appendLine("        /** The full type names of the aggregate's commands, `context.aggregate.Command`. */")
            appendLine("        $GENERATED")
            appendLine("        public object Commands {")
            commands.sortedBy { it.name }.forEachIndexed { index, command ->
                if (index > 0) {
                    appendLine()
                }
                appendLine("            /** `${comment(command.name)}` */")
                appendLine(
                    "            public const val ${commandName(command.name)}: kotlin.String = " +
                        literal("$context.${aggregate.name}.${command.name}"),
                )
            }
            appendLine("        }")
        }
    }

    /**
     * The object `Wait` of [context] in the context object [owner]: the hops of a wait chain in the context, its four
     * stages and, per subscription its contract lists that a wait chain can follow
     * (`SubscriptionManifest.isWaitHop`: not an `@OnRejected` handler alone), the hop of that subscription handling
     * the commit, named [waitName]. Two subscriptions, or a subscription and a stage, of one constant name fail the generation, naming
     * both: a constant renamed in silence would wait for another subscription than the one its name says.
     */
    private fun wait(owner: String, context: ContextManifest): String = buildString {
        val stages = listOf(
            Triple("ACCEPTED", "accepted", "a command of `${comment(context.name)}` passed admission"),
            Triple(
                "COMMITTED",
                "committed",
                "a command's events are appended to the log of `${comment(context.name)}`"
            ),
            Triple("SNAPSHOT", "snapshot", "the snapshot of `${comment(context.name)}` holds the commit"),
            Triple(
                "QUERYABLE",
                "queryable",
                "the store the snapshot queries of `${comment(context.name)}` read holds the commit",
            ),
        )
        val taken = stages.associate { (constant) -> constant to "the stage $constant" }.toMutableMap()
        val subscriptions = context.subscriptions.filter { it.isWaitHop }.sortedBy { it.name }.map { subscription ->
            val constant = waitName(subscription.name)
            taken[constant]?.let { other ->
                error(
                    "The subscription ${subscription.name} of ${context.name} and $other are both " +
                        "$owner.Wait.$constant: rename one of them, so that each hop names the subscription it waits for.",
                )
            }
            taken[constant] = "the subscription ${subscription.name}"
            constant to subscription
        }
        appendLine("    /**")
        appendLine(
            "     * The hops of a wait chain (`Wow-Wait`) in `${comment(context.name)}`: its stages, and each of its"
        )
        appendLine(
            "     * subscriptions handling the commit. `WaitPlan.of(...)` chains them, those of other contexts too."
        )
        appendLine("     */")
        appendLine("    $GENERATED")
        appendLine("    public object Wait {")
        stages.forEachIndexed { index, (constant, factory, doc) ->
            if (index > 0) {
                appendLine()
            }
            appendLine("        /** Until $doc. */")
            appendLine("        @kotlin.jvm.JvmField")
            appendLine("        public val $constant: $HOP = $HOP.$factory($owner.NAME)")
        }
        subscriptions.forEach { (constant, subscription) ->
            appendLine()
            appendLine(
                "        /** Until `${comment(subscription.name)}`, a ${subscription.kind.name.lowercase()}, " +
                    "handled the commit. */",
            )
            appendLine("        @kotlin.jvm.JvmField")
            appendLine("        public val $constant: $HOP = $HOP.handled(${literal(subscription.name)}, $owner.NAME)")
        }
        appendLine("    }")
    }

    /**
     * The constant name of the hop of the subscription [name] (`processor.function`): each segment in upper snake case
     * ([commandName]), joined by `__` for the dot; `OrderProcessSaga.onOrderSubmitted` is
     * `ORDER_PROCESS_SAGA__ON_ORDER_SUBMITTED`.
     */
    fun waitName(name: String): String = name.split('.').joinToString("__") { commandName(it) }

    /** The local name of [code] when it is a code of the aggregate [aggregate] of [context]; null otherwise. */
    private fun aggregateCodeOf(context: String, aggregate: String, code: String): String? =
        code.removePrefix("$context.$aggregate.").takeIf { it != code && '.' !in it }

    /**
     * The object `Errors` at [indent], documented by [doc], with a `const val` per code of [errors], named after
     * [nameOf] its code ([errorName]).
     */
    private fun errors(indent: String, doc: String, errors: List<ErrorManifest>, nameOf: (String) -> String): String =
        buildString {
            appendLine("$indent/** $doc */")
            appendLine("$indent$GENERATED")
            appendLine("${indent}public object Errors {")
            errors.distinctBy { errorName(nameOf(it.code)) }.forEachIndexed { index, error ->
                if (index > 0) {
                    appendLine()
                }
                appendLine("$indent    /** ${comment(error.description ?: error.code)} (HTTP ${error.status}) */")
                appendLine(
                    "$indent    public const val ${errorName(
                        nameOf(error.code)
                    )}: kotlin.String = ${literal(error.code)}"
                )
            }
            appendLine("$indent}")
        }

    /**
     * The constant name of the rejection code [name] (its local name, or what follows its context): its segments in
     * upper snake case joined by `__`, as [QueryFieldNames.constantOf] names a path; `invalid-item` is `INVALID_ITEM`.
     */
    fun errorName(name: String): String =
        QueryFieldNames.constantOf(name).let { if (it.first().isDigit()) "_$it" else it }

    /** The constant name of the command [name]: its words in upper snake case; `SubmitOrder` is `SUBMIT_ORDER`. */
    fun commandName(name: String): String = name
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .uppercase()
        .let { if (it.first().isDigit()) "_$it" else it }

    /** `eshop-inventory` → `EshopInventory`: the name's words, capitalised, without separators. */
    fun pascal(name: String): String = name.split('-', '_', '.', ' ').filter(String::isNotEmpty)
        .joinToString("") { word -> word.replaceFirstChar(Char::uppercaseChar) }

    /**
     * The object of the aggregate [name] in its context's object: [name] in Pascal case, followed by `Aggregate` when
     * the context's object declares that name itself ([CONTRACT_MEMBERS]), whether or not it does in this context, as
     * the TypeScript contract names its member: `errors` is `ErrorsAggregate`, `wait` is `WaitAggregate` (#6945).
     */
    private fun aggregateObject(name: String): String =
        pascal(name).let { if (it in CONTRACT_MEMBERS) "${it}Aggregate" else it }

    /** The members of a context's object besides its aggregates: its name, its shared codes and its hops. */
    private val CONTRACT_MEMBERS = setOf("NAME", "Errors", "Wait")

    /** [value] as a Kotlin string literal. */
    private fun literal(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n") + "\""

    /** [text] on one line of a KDoc comment, which it cannot close. */
    private fun comment(text: String): String = text.replace("*/", "*&#47;").replace(Regex("\\s*\\n\\s*"), " ")
}

/**
 * The paths of an aggregate's state a query names, from its schema in a contract: those the compiler's
 * `<State>Properties` holds for the same state, as Jackson writes it and the schema describes it, each with the Kotlin
 * type of its values as the contract tells them.
 *
 * Each property of an object schema with a [QueryFieldNames.isSegment] name is a path, `state.<name>`; the walk
 * descends into an object's properties and into the items of an array (an element's fields are named after the
 * array's path), through `$ref`s and a nullable alternative (`anyOf`/`oneOf` with one non-null schema). A schema
 * without properties is a leaf: a map, an enum, a value, a polymorphic type (`anyOf` of its subtypes), and an object
 * the contract withholds; so is a definition already on the path.
 *
 * A path's type is the one its state's view gives the property ([StateViewSources.typeOf]): the views and their
 * contract's query fields share one mapping, so `STATE__STATUS` holds the view's `Status`. A value no type names is
 * `Any?`.
 */
internal object StateFields {
    private const val REF = "\$ref"
    private const val PROPERTIES = "properties"
    private const val ITEMS = "items"
    private const val REQUIRED = "required"
    private const val ANY = "kotlin.Any?"

    /** A path of the state and the Kotlin [type] of its values. */
    data class Field(val path: String, val type: String)

    /**
     * The paths of the state whose schema reference is [state], in [defs], each typed by [typeOf] (the Kotlin type of
     * a property's schema at a field, null when it has none: then `Any?`); empty when [defs] lacks the state.
     */
    fun of(
        state: String,
        defs: Map<String, Any?>,
        typeOf: (schema: Any?, field: String) -> StateViewSources.Type? = { _, _ -> null },
    ): List<Field> {
        val fields = linkedMapOf<String, Field>()
        Walk(defs, typeOf, fields).collect(mapOf(REF to state), QueryFieldNames.STATE, emptySet())
        return fields.values.toList()
    }

    private class Walk(
        val defs: Map<String, Any?>,
        val typeOf: (Any?, String) -> StateViewSources.Type?,
        val fields: MutableMap<String, Field>,
    ) {
        fun collect(schema: Any?, path: String, seen: Set<String>) {
            val node = schema as? Map<*, *> ?: return
            val ref = node[REF] as? String
            if (ref != null) {
                val key = Manifest.schemaKey(ref)
                if (key !in seen) {
                    collect(defs[key], path, seen + key)
                }
                return
            }
            nullable(node)?.let { return collect(it, path, seen) }
            node[ITEMS]?.let { return collect(it, path, seen) }
            val required = (node[REQUIRED] as? List<*>).orEmpty().toSet()
            (node[PROPERTIES] as? Map<*, *>)?.forEach { (name, property) ->
                if (name !is String || !QueryFieldNames.isSegment(name)) return@forEach
                val child = "$path.$name"
                // Nullable as the view's property is: when its schema accepts null or does not require it.
                val type = typeOf(property, child)?.let { if (name in required) it.text else "${it.name}?" } ?: ANY
                if (fields.putIfAbsent(QueryFieldNames.constantOf(child), Field(child, type)) == null) {
                    collect(property, child, seen)
                }
            }
        }
    }

    /** The one schema of a nullable alternative (`anyOf`/`oneOf` of it and `null`); null for any other node. */
    private fun nullable(node: Map<*, *>): Any? {
        val alternatives = (node["anyOf"] ?: node["oneOf"]) as? List<*> ?: return null
        return alternatives.filterNot { (it as? Map<*, *>)?.get("type") == "null" }.singleOrNull()
    }
}
