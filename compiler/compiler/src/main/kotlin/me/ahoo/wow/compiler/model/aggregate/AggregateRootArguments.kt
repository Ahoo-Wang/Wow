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

package me.ahoo.wow.compiler.model.aggregate

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.compiler.model.symbol.AnnotationArguments

/**
 * The arguments of an aggregate root's `@AggregateRoot`, read and checked for the model processor. Each function takes
 * the root's `@AggregateRoot` read ([me.ahoo.wow.compiler.model.symbol.argumentsOf]), null when one of its arguments has
 * no value, a problem of the root already: it then answers the annotation's default, to go on checking the root.
 */
internal object AggregateRootArguments {
    private const val LIFECYCLE = "lifecycle"
    private const val OWNERSHIP = "ownership"
    private const val ID_SOURCE = "idSource"
    private const val PUBLISHED = "published"
    private const val PUBLISH_STATE = "publishState"
    private const val TOPIC = "topic"
    private const val SNAPSHOT = "snapshot"
    private const val STATE_REVISION = "stateRevision"
    private val TRANSFER_OWNER: String = Lifecycle.nameOf(TransferOwner::class.java)!!

    /** The lifecycle commands' system names, by their classes' names. */
    private val LIFECYCLE_COMMANDS: Map<String, String> = Lifecycle.COMMANDS.entries.associate { (name, type) ->
        type.name to name
    }

    /**
     * The aggregate's published stream as `@AggregateRoot(published, publishState, topic)` declares it;
     * a topic Kafka would refuse is a problem ([Publication.topicProblem]).
     */
    fun publication(arguments: AnnotationArguments?, problems: MutableList<String>): Publication {
        val topic = arguments?.string(TOPIC).orEmpty().ifEmpty { null }
        topic?.let(Publication::topicProblem)?.let { problems += it }
        return Publication(
            published = arguments?.boolean(PUBLISHED) ?: true,
            includeState = arguments?.boolean(PUBLISH_STATE) ?: true,
            topic = topic,
        )
    }

    /** Whether the aggregate keeps snapshots, as `@AggregateRoot(snapshot)` says: true by default. */
    fun snapshot(arguments: AnnotationArguments?): Boolean = arguments?.boolean(SNAPSHOT) ?: true

    /**
     * The revision of the aggregate's state structure, as `@AggregateRoot(stateRevision)` of [arguments] says: 1 by
     * default; one below 1 is a problem.
     */
    fun stateRevision(arguments: AnnotationArguments?, problems: MutableList<String>): Int {
        val revision = arguments?.int(STATE_REVISION) ?: 1
        if (revision < 1) {
            problems += "@AggregateRoot(stateRevision) is $revision: a state revision starts at 1"
        }
        return revision
    }

    /**
     * The aggregate's ownership as `@AggregateRoot(ownership)` declares it: the name of the
     * `Ownership` entry, `OWNER` by default.
     */
    fun ownership(arguments: AnnotationArguments?): String = arguments?.enumName(OWNERSHIP) ?: Ownership.OWNER.name

    /**
     * Where the aggregate's id comes from as `@AggregateRoot(idSource)` declares it: the name of the
     * `AggregateIdSource` entry, `ASSIGNED` by default. An id that is its owner's needs an aggregate of its owner: one
     * of its tenant ([ownership] `TENANT`) is a problem.
     */
    fun idSource(arguments: AnnotationArguments?, ownership: String, problems: MutableList<String>): String {
        val source = arguments?.enumName(ID_SOURCE) ?: AggregateIdSource.ASSIGNED.name
        if (source == AggregateIdSource.OWNER.name && ownership == Ownership.TENANT.name) {
            problems += "@AggregateRoot(idSource = OWNER) needs an aggregate that belongs to its owner, but it belongs to " +
                "its tenant (ownership = TENANT): it has no owner to take its id from"
        }
        return source
    }

    /**
     * The system names of the lifecycle commands `@AggregateRoot(lifecycle)` lists, sorted; a class
     * that is not a lifecycle command is a problem. Without the argument, every one. An aggregate of its tenant
     * has no owner to transfer, and one whose id is its owner's ([idSource] `OWNER`) cannot transfer it:
     * TransferOwner is left out of every one, and a problem when listed with fewer.
     */
    fun lifecycle(
        arguments: AnnotationArguments?,
        ownership: String,
        idSource: String,
        problems: MutableList<String>,
    ): List<String> {
        val tenant = ownership == Ownership.TENANT.name
        val owned = idSource == AggregateIdSource.OWNER.name
        val fixed = tenant || owned
        val listed = arguments?.types(LIFECYCLE)
            ?: return AggregateBinding.ALL_LIFECYCLE_COMMANDS.filterNot { fixed && it == TRANSFER_OWNER }
        val commands = LIFECYCLE_COMMANDS
        val names = listed.mapNotNull { type ->
            val name = type.declaration.qualifiedName?.asString()
            commands[name].also {
                if (it == null) {
                    problems += "@AggregateRoot(lifecycle) lists $name, which is not a lifecycle command: one of " +
                        commands.keys.sorted().joinToString { command -> command.substringAfterLast('.') }
                }
            }
        }.distinct().sorted()
        if (!fixed || TRANSFER_OWNER !in names) {
            return names
        }
        // Every one, as by default: an aggregate whose owner is fixed drops TransferOwner.
        if (names != AggregateBinding.ALL_LIFECYCLE_COMMANDS) {
            problems += if (tenant) {
                "@AggregateRoot(lifecycle) lists TransferOwner, but the aggregate belongs to its tenant " +
                    "(ownership = TENANT): it has no owner to transfer"
            } else {
                "@AggregateRoot(lifecycle) lists TransferOwner, but the aggregate's id is its owner's " +
                    "(idSource = OWNER): its owner cannot be transferred"
            }
        }
        return names - TRANSFER_OWNER
    }
}
