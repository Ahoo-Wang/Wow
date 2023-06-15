package me.ahoo.wow.messaging.dispatcher

import org.junit.jupiter.api.Test

class AbstractMessageDispatcherTest {
    @Test
    fun run() {
        val messageDispatcher = object : AbstractMessageDispatcher<String>() {
            override val topics: Set<Any>
                get() = setOf("topic")

            override fun start() = Unit

            override val name: String
                get() = "messageDispatcher"
        }
        messageDispatcher.run()
        messageDispatcher.close()
    }

    @Test
    fun runIfEmpty() {
        val messageDispatcher = object : AbstractMessageDispatcher<String>() {
            override val topics: Set<Any>
                get() = setOf()

            override fun start() = Unit

            override val name: String
                get() = "messageDispatcher"
        }
        messageDispatcher.run()
        messageDispatcher.close()
    }
}
