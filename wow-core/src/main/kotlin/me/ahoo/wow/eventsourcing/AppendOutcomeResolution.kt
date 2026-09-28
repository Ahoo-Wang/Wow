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
package me.ahoo.wow.eventsourcing

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.exception.recoverable
import reactor.core.publisher.Mono

private val log = KotlinLogging.logger {}

private enum class SlotHolder {
    SELF,
    OTHER,
    NONE,
}

/**
 * Appends [eventStream], and when the append fails, asks the store whether it committed anyway.
 *
 * A failed append does not prove that nothing was written: a timeout or a dropped connection can
 * arrive after the write reached the store. Reporting such a command as failed, or deciding it
 * again, loses its events. `(aggregateId, version)` is unique in every store, so whichever stream
 * holds [eventStream]'s version slot holds it for good:
 *
 * - [eventStream] itself: the append committed, and this completes normally.
 * - another stream: [eventStream] can never commit, and the original failure is returned.
 * - nobody, after a recoverable failure: the write is retried once with the same stream. The
 *   slot decides between the retry and a write still in flight, then it is read again.
 * - nobody, otherwise, or the store cannot be read: the original failure is returned.
 */
fun EventStore.appendResolvingOutcome(eventStream: DomainEventStream): Mono<Void> =
    append(eventStream).onErrorResume { failure ->
        slotHolder(eventStream, failure).flatMap { holder ->
            when (holder) {
                SlotHolder.SELF -> committedDespite(eventStream, failure)
                SlotHolder.OTHER -> Mono.error(failure)
                SlotHolder.NONE -> if (failure.recoverable == RecoverableType.RECOVERABLE) {
                    rewrite(eventStream, failure)
                } else {
                    Mono.error(failure)
                }
            }
        }
    }

private fun EventStore.rewrite(eventStream: DomainEventStream, failure: Throwable): Mono<Void> =
    append(eventStream).onErrorResume { rewriteFailure ->
        failure.addSuppressed(rewriteFailure)
        slotHolder(eventStream, failure).flatMap { holder ->
            if (holder == SlotHolder.SELF) {
                committedDespite(eventStream, failure)
            } else {
                Mono.error(failure)
            }
        }
    }

private fun EventStore.slotHolder(eventStream: DomainEventStream, failure: Throwable): Mono<SlotHolder> =
    load(eventStream.aggregateId, eventStream.version, eventStream.version)
        .next()
        .map { if (it.id == eventStream.id) SlotHolder.SELF else SlotHolder.OTHER }
        .defaultIfEmpty(SlotHolder.NONE)
        .onErrorResume { readFailure ->
            failure.addSuppressed(readFailure)
            Mono.error(failure)
        }

private fun committedDespite(eventStream: DomainEventStream, failure: Throwable): Mono<Void> {
    log.warn(failure) {
        "Append of ${eventStream.aggregateId} version[${eventStream.version}] " +
            "requestId[${eventStream.requestId}] failed, but the stream is committed."
    }
    return Mono.empty()
}
