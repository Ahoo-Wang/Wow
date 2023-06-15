package me.ahoo.wow.messaging.dispatcher

import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test

class SafeSubscriberTest {
    @Test
    fun subscribe() {
        val completed = Sinks.empty<String>()
        val safeSubscriber = object : SafeSubscriber<String>() {
            override val name: String
                get() = "name"

            override fun safeOnNext(value: String) {
                completed.tryEmitEmpty()
            }
        }

        Mono.just("Signal")
            .subscribe(safeSubscriber)
        completed.asMono().test().verifyComplete()
    }

    @Test
    fun subscribeHookOnError() {
        val completed = Sinks.empty<String>()
        val safeSubscriber = object : SafeSubscriber<String>() {
            override val name: String
                get() = "name"

            override fun safeOnNext(value: String) {
                throw IllegalStateException("safeOnNext")
            }

            override fun safeOnNextError(value: String, throwable: Throwable) {
                completed.tryEmitEmpty()
            }
        }

        Mono.just("Signal")
            .subscribe(safeSubscriber)
        completed.asMono().test().verifyComplete()
    }
}
