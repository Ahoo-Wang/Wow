package me.ahoo.wow.command.wait

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class CommandWaitNotifierKtTest {

    @Test
    fun isLocalCommandIfBlank() {
        assertThat(isLocalCommand(""), equalTo(false))
    }
}
