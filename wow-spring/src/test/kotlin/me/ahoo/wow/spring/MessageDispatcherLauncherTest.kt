package me.ahoo.wow.spring

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.messaging.MessageDispatcher
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class MessageDispatcherLauncherTest {

    @Test
    fun start() {
        val messageDispatcher = mockk<MessageDispatcher> {
            every { run() } returns Unit
            every { close() } returns Unit
        }
        val messageDispatcherLauncher = MessageDispatcherLauncher(messageDispatcher)
        messageDispatcherLauncher.start()
        messageDispatcherLauncher.start()
        verify(exactly = 1) {
            messageDispatcher.run()
        }
        assertThat(messageDispatcherLauncher.isRunning, equalTo(true))
        messageDispatcherLauncher.stop()
        messageDispatcherLauncher.stop()
        verify(exactly = 1) {
            messageDispatcher.close()
        }
        assertThat(messageDispatcherLauncher.isRunning, equalTo(false))
    }
}
