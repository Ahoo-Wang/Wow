package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CommandWaitNotifierKtTest {

    @Test
    fun isLocalCommandIfBlank() {
        isLocalCommand("").assert().isFalse()
    }
}
