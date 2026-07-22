package me.ahoo.wow.spring

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MessageDispatcherLauncherTest {

    @Test
    fun `should start message dispatcher`() {
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

    @Test
    fun `should allow start retry after dispatcher start fails`() {
        val messageDispatcher = mockk<MessageDispatcher> {
            every { start() } throws IllegalStateException("start failed") andThen Unit
            every { stop(any()) } returns Unit
        }
        val launcher = MessageDispatcherLauncher(messageDispatcher, Duration.ofSeconds(60))

        assertThrownBy<IllegalStateException> {
            launcher.start()
        }
        launcher.isRunning.assert().isFalse()

        launcher.start()

        launcher.isRunning.assert().isTrue()
        verify(exactly = 2) {
            messageDispatcher.start()
        }
        verify(exactly = 1) {
            messageDispatcher.stop(Duration.ofSeconds(60))
        }
        confirmVerified(messageDispatcher)
    }

    @Test
    fun `should retain cleanup responsibility when start and cleanup fail`() {
        val startFailure = IllegalStateException("start failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val messageDispatcher = mockk<MessageDispatcher> {
            every { start() } throws startFailure
            every { stop(any()) } throws cleanupFailure andThen Unit
        }
        val launcher = MessageDispatcherLauncher(messageDispatcher, Duration.ofSeconds(60))

        val thrown = runCatching {
            launcher.start()
        }.exceptionOrNull()!!

        thrown.assert().isSameAs(startFailure)
        thrown.suppressedExceptions.assert().containsExactly(cleanupFailure)
        launcher.isRunning.assert().isTrue()

        launcher.stop()

        launcher.isRunning.assert().isFalse()
        verify(exactly = 1) {
            messageDispatcher.start()
        }
        verify(exactly = 2) {
            messageDispatcher.stop(Duration.ofSeconds(60))
        }
        confirmVerified(messageDispatcher)
    }

    @Test
    fun `should allow stop retry after dispatcher stop fails`() {
        val messageDispatcher = mockk<MessageDispatcher> {
            every { start() } returns Unit
            every { stop(any()) } throws IllegalStateException("stop failed") andThen Unit
        }
        val launcher = MessageDispatcherLauncher(messageDispatcher, Duration.ofSeconds(60))
        launcher.start()

        assertThrownBy<IllegalStateException> {
            launcher.stop()
        }
        launcher.isRunning.assert().isTrue()

        launcher.stop()

        launcher.isRunning.assert().isFalse()
        verify(exactly = 1) {
            messageDispatcher.start()
        }
        verify(exactly = 2) {
            messageDispatcher.stop(Duration.ofSeconds(60))
        }
        confirmVerified(messageDispatcher)
    }

    @Test
    fun `should serialize concurrent start and stop`() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val messageDispatcher = mockk<MessageDispatcher> {
            every { start() } answers {
                startEntered.countDown()
                check(releaseStart.await(5, TimeUnit.SECONDS))
            }
            every { stop(any()) } answers {
                stopEntered.countDown()
            }
        }
        val launcher = MessageDispatcherLauncher(messageDispatcher, Duration.ofSeconds(60))
        val executor = Executors.newFixedThreadPool(2)

        try {
            val startFuture = executor.submit {
                launcher.start()
            }
            check(startEntered.await(5, TimeUnit.SECONDS))
            val stopFuture = executor.submit {
                stopAttempted.countDown()
                launcher.stop()
            }

            check(stopAttempted.await(5, TimeUnit.SECONDS))
            stopEntered.await(200, TimeUnit.MILLISECONDS).assert().isFalse()
            releaseStart.countDown()
            startFuture.get(5, TimeUnit.SECONDS)
            stopFuture.get(5, TimeUnit.SECONDS)

            launcher.isRunning.assert().isFalse()
            stopEntered.count.assert().isZero()
            verify(exactly = 1) {
                messageDispatcher.start()
                messageDispatcher.stop(Duration.ofSeconds(60))
            }
            confirmVerified(messageDispatcher)
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
        }
    }
}
