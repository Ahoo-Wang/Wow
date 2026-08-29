/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class CursorPageTest {
    @Test
    fun `should round trip cursor page`() {
        val page = CursorPage(listOf("one"), "next")
        val json = jacksonObjectMapper().writeValueAsString(page)
        jacksonObjectMapper().readValue(json, CursorPage::class.java)
            .nextCursor.assert().isEqualTo("next")
    }

    @Test
    fun `should support missing next cursor`() {
        CursorPage(listOf("one"), null).nextCursor.assert().isNull()
    }
}
