package me.ahoo.wow.redis.eventsourcing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.isWrapped
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.unwrap
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.wrap
import org.junit.jupiter.api.Test

class RedisWrappedKeyTest {

    @Test
    fun `should check if key is wrapped`() {
        "".isWrapped().assert().isFalse()
        "{key}".isWrapped().assert().isTrue()
    }

    @Test
    fun `should wrap key`() {
        "".wrap().assert().isEqualTo("{}")
        "key".wrap().assert().isEqualTo("{key}")
    }

    @Test
    fun `should unwrap key`() {
        "".unwrap().assert().isEqualTo("")
        "key".unwrap().assert().isEqualTo("key")
        "{key}".unwrap().assert().isEqualTo("key")
    }
}
