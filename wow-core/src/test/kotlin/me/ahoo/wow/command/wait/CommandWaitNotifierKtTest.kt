package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CommandWaitNotifierKtTest {

    @Test
    fun `should is local command if blank`() {
        isLocalWaitStrategy("").assert().isFalse()
    }
}
