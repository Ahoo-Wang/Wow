package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.parameters.RequestBody
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRefRequestBody
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBodyRef
import me.ahoo.wow.openapi.RequestBodyRef.Companion.with
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class RequestBodyRefTest {

    @Test
    fun toRefRequestBody() {
        val requestBody = "test".toRefRequestBody()
        assertThat(requestBody.`$ref`, equalTo("#/components/requestBodies/test"))
    }

    @Test
    fun toRequestBodyRef() {
        val requestBodyRef = RequestBodyRefTest::class.java.toRequestBodyRef()
        assertThat(requestBodyRef.ref.`$ref`, equalTo("#/components/requestBodies/wow.RequestBodyRefTest"))
        val map = mutableMapOf<String, RequestBody>().with(requestBodyRef)
        assertThat(map.size, equalTo(1))
    }
}
