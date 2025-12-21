package me.ahoo.wow.spring

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import org.junit.jupiter.api.Test
import java.time.Duration

class MessageDispatcherLauncherTest {

    @Test
    fun start() {
        val messageDispatcher = mockk<MessageDispatcher> {
            every { start() } returns Unit
            every { stop(any()) } returns Unit
        }
        val messageDispatcherLauncher = MessageDispatcherLauncher(messageDispatcher, Duration.ofSeconds(60))
        messageDispatcherLauncher.start()
        messageDispatcherLauncher.start()
        verify(exactly = 1) {
            messageDispatcher.start()
        }
        messageDispatcherLauncher.isRunning.assert().isTrue()
        messageDispatcherLauncher.stop()
        messageDispatcherLauncher.stop()
        verify(exactly = 1) {
            messageDispatcher.stop(Duration.ofSeconds(60))
        }
        messageDispatcherLauncher.isRunning.assert().isFalse()
        confirmVerified(messageDispatcher)
    }
}
