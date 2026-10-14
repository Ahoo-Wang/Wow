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

package me.ahoo.wow.compiler.model

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

/**
 * The V10 annotation set (design 01 §3, decision D67). The V10 annotations keep the package
 * `me.ahoo.wow.api.annotation` and the names of 9.x's, without the attributes only 9.x reads (decision D85); the
 * model processor recognizes only them. Any other annotation of that package on a V10 model (one compiled against 9.x
 * `wow-api`) is a compile error that points to the migration skill, and the identity annotations on a command's fields
 * are errors too (decision D68).
 */
internal object V10Annotations {
    const val PACKAGE = "me.ahoo.wow.api.annotation"

    /** The V10 set, by simple name. */
    val NAMES = setOf(
        "BoundedContext",
        "AggregateRoot",
        "OnCommand",
        "OnSourcing",
        "CreateAggregate",
        "AllowCreate",
        "MountedCommand",
        "AfterCommand",
        "Name",
        "Revision",
        "Alias",
        "Projection",
        "Reactor",
        "Saga",
        "OnEvent",
        "OnRejected",
        "OnSchedule",
        "Upgrade",
        "Blocking",
        "Description",
        "Summary",
        "ErrorCode",
        "Retry",
        "WowSpi",
        // Not a domain annotation: it marks the framework's own internals.
        "InternalWowApi",
    )

    /** What replaces a removed 9.x annotation; the others are removed with no counterpart. */
    private val REPLACEMENTS = mapOf(
        "OnError" to "reject with a Rejection, and compensate in a @Saga",
        "PreparableKey" to "reserve the key with DecideScope.reserve",
        "AggregateRoute" to "routes are materialized from the Manifest",
        "CommandRoute" to "routes are materialized from the Manifest",
        "OnStateEvent" to "use @OnEvent, which receives a StateEvent",
        "OnMessage" to "use @OnEvent",
        "ProjectionProcessor" to "use @Projection",
        "EventProcessor" to "use @Reactor",
        "StatelessSaga" to "use @Saga",
        "Event" to "use @Revision for the event's revision",
        "AggregateName" to "use @Name on the aggregate root",
        "AggregateId" to IDENTITY,
        "TenantId" to IDENTITY,
        "OwnerId" to IDENTITY,
        "AggregateVersion" to IDENTITY,
        "StaticAggregateId" to IDENTITY,
        "StaticTenantId" to IDENTITY,
    )

    /**
     * The annotations only a subscription processor or its handler takes: `@Retry` (roadmap P.3) and `@OnSchedule`
     * (decision D198).
     */
    private val PROCESSOR_NAMES = setOf("Retry", "OnSchedule")

    /** The identity annotations a command body no longer carries (decision D68). */
    private val IDENTITY_NAMES = setOf("AggregateId", "TenantId", "OwnerId", "AggregateVersion")

    private const val IDENTITY =
        "a command carries no identity: it is Command.ref, from the route or the command message, and the scope " +
            "comes from the principal (decision D68)"

    const val MIGRATION = "the wow-migrate skill rewrites 9.x code into V10 (design 07 §2)"

    /**
     * The problems of the annotations on [annotated], described as [where]. A command's identity annotations get the
     * D68 message. Only a subscription [processor] or its handler takes `@Retry`.
     */
    fun problems(annotated: KSAnnotated, where: String, processor: Boolean = false): List<String> =
        annotated.annotations.mapNotNull { annotation ->
            val declaration = annotation.annotationType.resolve().declaration
            val qualifiedName = declaration.qualifiedName?.asString() ?: return@mapNotNull null
            if (!qualifiedName.startsWith("$PACKAGE.")) {
                return@mapNotNull null
            }
            val simpleName = qualifiedName.removePrefix("$PACKAGE.").substringBefore('.')
            if (simpleName in PROCESSOR_NAMES && !processor) {
                return@mapNotNull "$where is annotated @$simpleName, which only a subscription processor " +
                    "(@Projection, @Reactor, @Saga) or its @OnEvent handler takes"
            }
            if (simpleName in NAMES) {
                return@mapNotNull null
            }
            val replacement = REPLACEMENTS[simpleName] ?: "it is removed with no counterpart"
            val identity = if (simpleName in IDENTITY_NAMES) " (decision D68)" else ""
            "$where is annotated @$simpleName, which V10 removed$identity: $replacement; $MIGRATION"
        }.distinct().toList()

    /**
     * The problems of the annotations on [type] and on its members: its fields (properties, constructor parameters,
     * getters) and its functions with their parameters. [role] says what the type is to the model, such as "command".
     */
    fun problemsOfType(type: KSClassDeclaration, role: String): List<String> {
        val name = type.simpleName.asString()
        val where = "$role $name"
        val constructorParameters = (type.primaryConstructor?.parameters.orEmpty()).flatMap {
            problems(it, "$where's field ${it.name?.asString()}")
        }
        val properties = type.getDeclaredProperties().flatMap {
            val field = "$where's field ${it.simpleName.asString()}"
            problems(it, field) + listOfNotNull(it.getter).flatMap { getter -> problems(getter, field) }
        }
        val functions = type.getDeclaredFunctions().flatMap { problemsOfFunction(it, where) }
        return (problems(type, where) + constructorParameters + properties + functions).distinct()
    }

    /**
     * The problems of the annotations on [function] of the type described as [owner], and on its parameters; a
     * [processor]'s function may take `@Retry`.
     */
    fun problemsOfFunction(function: KSFunctionDeclaration, owner: String, processor: Boolean = false): List<String> {
        val where = "$owner's function ${function.simpleName.asString()}"
        return problems(function, where, processor) + function.parameters.flatMap {
            problems(it, "$where's parameter ${it.name?.asString()}")
        }
    }
}
