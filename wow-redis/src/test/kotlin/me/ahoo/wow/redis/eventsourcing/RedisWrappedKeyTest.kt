package me.ahoo.wow.redis.eventsourcing

import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.isWrapped
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.unwrap
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.wrap
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class RedisWrappedKeyTest {

    @Test
    fun isWrapped() {
        assertThat("".isWrapped(), equalTo(false))
        assertThat("{key}".isWrapped(), equalTo(true))
    }

    @Test
    fun wrap() {
        assertThat("".wrap(), equalTo("{}"))
        assertThat("key".wrap(), equalTo("{key}"))
    }

    @Test
    fun unwrap() {
        assertThat("".unwrap(), equalTo(""))
        assertThat("key".unwrap(), equalTo("key"))
        assertThat("{key}".unwrap(), equalTo("key"))
    }
}
