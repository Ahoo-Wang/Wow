/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.spi.json

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import java.util.Locale

/**
 * Every locale reads back from the text Wow 9.x wrote of it, Java's `Locale.toString()` (Jackson 2's default), on every
 * Jackson minor ([LocaleTexts]): the JDK's locales, among them each form with a script or extensions it writes
 * (`zh_CN_#Hans`, `az__#Cyrl`, `de_DE_1996_#Latn`, `ja_JP_JP_#u-ca-japanese`), and the forms the JDK ships none of.
 */
class LocaleFormsTest {
    private val built = listOf(
        "und-Latn-US",
        "zh-Hant-TW-x-java",
        "en-US-u-ca-buddhist-x-foo",
        "en-x-private",
        "sr-Latn-RS-u-nu-latn",
        "de-Latn-DE-1996",
        "en-x-a-b",
    ).map(Locale::forLanguageTag)

    private val locales = Locale.getAvailableLocales().toList() + built

    private fun read(text: String): Result<Locale> =
        runCatching { WowJson.mapper.readValue("\"$text\"", Locale::class.java) }

    @Test
    fun `every locale should read back from Java's form of it, its script and extensions kept`() {
        val wrong = locales.mapNotNull { locale ->
            val text = locale.toString()
            read(text).fold(
                { read -> "$text read as ${read.toLanguageTag()}, not ${locale.toLanguageTag()}".takeIf { read != locale } },
                { "$text refused: ${it.message?.lineSequence()?.first()}" },
            )
        }
        wrong.assert().describedAs(wrong.joinToString("\n")).isEmpty()
        // The forms with a script or extensions are among them, of every shape.
        val scripted = locales.map(Locale::toString).filter { "_#" in it }
        scripted.assert().contains("zh_CN_#Hans", "az__#Cyrl", "_US_#Latn", "zh_TW_#Hant_x-java", "en__#x-private")
    }

    @Test
    fun `a text of Java's form that no locale writes should be refused`() {
        listOf("zh_CN_#Hanss", "zh_CN_#Hans_", "en__#-x", "en__#Latn_q", "zh_CN_#", "en_#Latn").forEach { text ->
            read(text).exceptionOrNull().assert().describedAs(text).isInstanceOf(JacksonException::class.java)
        }
    }
}
