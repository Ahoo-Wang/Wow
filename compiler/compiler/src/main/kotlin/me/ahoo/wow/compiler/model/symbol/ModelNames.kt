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

package me.ahoo.wow.compiler.model.symbol

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.Schedules

/** The names and forms the model processor and its resolvers share. */
internal object ModelNames {
    val BLOCKING = Blocking::class.java.name
    val ON_COMMAND_ANNOTATION: String = OnCommand::class.java.name
    val ON_SOURCING_ANNOTATION: String = OnSourcing::class.java.name
    val AFTER_COMMAND_ANNOTATION: String = AfterCommand::class.java.name
    const val ANY = "kotlin.Any"
    val ANY_TYPES = setOf(ANY, "java.lang.Object")

    /** The system events every aggregate may commit (lifecycle and schedule), by Kotlin name. */
    val SYSTEM_EVENTS: Set<String> =
        (Lifecycle.EVENTS.values + Schedules.EVENTS.values).map { it.canonicalName }.toSet()

    /** The lifecycle commands (design 01 §6), by Kotlin name: an after hook may include them. */
    val LIFECYCLE_COMMANDS: Set<String> = Lifecycle.COMMANDS.values.map { it.canonicalName }.toSet()

    /** The system commands every aggregate decides (lifecycle and schedule), by Kotlin name. */
    val SYSTEM_COMMANDS: Set<String> = LIFECYCLE_COMMANDS + Schedules.COMMANDS.values.map { it.canonicalName }
    const val ON_SOURCING = "onSourcing"
    const val STRING = "kotlin.String"
    const val DECIDE_SCOPE = "me.ahoo.wow.api.decide.DecideScope"
    const val COMMAND = "me.ahoo.wow.api.decide.Command"

    /** Packages of 9.x framework types; Wow's own platform domains live under `me.ahoo.wow` too. */
    val FRAMEWORK_PACKAGES = listOf(
        "me.ahoo.wow.api.",
        "me.ahoo.wow.command.",
        "me.ahoo.wow.event.",
        "me.ahoo.wow.eventsourcing.",
        "me.ahoo.wow.messaging.",
        "me.ahoo.wow.modeling.",
        "me.ahoo.wow.infra.",
        "me.ahoo.wow.ioc.",
        "me.ahoo.wow.saga.",
    )
    val REACTOR_TYPES = setOf(
        "reactor.core.publisher.Mono",
        "reactor.core.publisher.Flux",
        "org.reactivestreams.Publisher",
    )
}

/**
 * Whether [function], or one it overrides (an annotation is not inherited by an override), is `@Blocking`: the
 * kernel runs it on a virtual thread (decision D227). On a function returning a Reactor publisher it is a
 * [warnings] entry: the publisher is subscribed on the virtual thread, but a publisher should not block.
 */
internal fun blocking(function: KSFunctionDeclaration, reactor: Boolean, warnings: MutableList<String>): Boolean {
    val blocking = function.annotatedIn(ModelNames.BLOCKING) != null
    if (blocking && reactor) {
        warnings += "${function.simpleName.asString()} is annotated @Blocking and returns a Reactor publisher: " +
            "it is called on a virtual thread, but a publisher should not block; make it a plain function"
    }
    return blocking
}

/** The Reactor publisher type [function] returns, or null when it returns none. */
internal fun reactorReturn(function: KSFunctionDeclaration): String? =
    function.returnType?.resolve()?.declaration?.qualifiedName?.asString()?.takeIf { it in ModelNames.REACTOR_TYPES }

/** This type's qualified name, with its type arguments (`*` for a star projection). */
internal fun KSType.render(): String {
    val name = declaration.qualifiedName!!.asString()
    if (arguments.isEmpty()) {
        return name
    }
    return name + arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.render() ?: "*" }
}
