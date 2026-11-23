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

import java.util.IllformedLocaleException
import java.util.Locale

/**
 * The texts of a [Locale] Wow reads before Jackson's reader, which reads them otherwise on each Jackson 3 minor, or
 * not at all:
 *
 * - **A text of one subtag** (no `-` or `_`), `"en"`, is a BCP 47 tag ([subtag]): Jackson 3.1 reads it as a language
 *   of that name and 3.2 as a tag, failing on one that is none.
 * - **Java's form of a locale with a script or extensions** (`Locale.toString()`), `"zh_CN_#Hans"`, which Wow 9.x
 *   wrote (Jackson 2's default) and Jackson 2 read back ([javaForm]): Jackson 3 splits it at its underscores and keeps
 *   `#Hans` as a variant, so the script is lost.
 *
 * Any other text is Jackson's, which reads it alike on every minor: a BCP 47 tag with `-`, and Java's form without
 * `_#`, `"zh_CN"`.
 */
internal object LocaleTexts {
    /** What precedes a script or the extensions in `Locale.toString()`. */
    private const val MARKER = "_#"

    private const val SCRIPT_LENGTH = 4

    /** The locale [text] is, by its form, or null for a text Jackson reads; [refuse] for one that is no locale. */
    fun read(text: String, refuse: (String) -> Nothing): Locale? = when {
        text.isEmpty() -> null
        MARKER in text -> javaForm(text) ?: refuse("$text is not the text of a locale.")
        text.none { it == '-' || it == '_' } -> subtag(text) ?: refuse("$text is not a language tag.")
        else -> null
    }

    /** [text], a single subtag, as a BCP 47 tag; null when it is none. */
    private fun subtag(text: String): Locale? = try {
        Locale.Builder().setLanguageTag(text).build()
    } catch (_: IllformedLocaleException) {
        null
    }

    /**
     * The locale whose `toString()` is [text]: `language_region_variant`, then `_#` and a script, then `_` and the
     * extensions, or `_#` and the extensions alone (`"zh_CN_#Hans"`, `"az__#Cyrl"`, `"_US_#Latn"`,
     * `"zh_TW_#Hant_x-java"`, `"en__#x-private"`). Null when no locale writes it: what is read is checked by writing it.
     */
    private fun javaForm(text: String): Locale? {
        val marker = text.indexOf(MARKER)
        val head = text.substring(0, marker).split('_', limit = 3)
        val language = head[0]
        val region = head.getOrElse(1) { "" }
        val variant = head.getOrElse(2) { "" }
        val tail = text.substring(marker + MARKER.length)
        val scripted = tail.length >= SCRIPT_LENGTH && tail.take(SCRIPT_LENGTH).all(Char::isLetter) &&
            (tail.length == SCRIPT_LENGTH || tail[SCRIPT_LENGTH] == '_')
        val script = if (scripted) tail.take(SCRIPT_LENGTH) else ""
        val extensions = extensionsOf(if (scripted) tail.drop(SCRIPT_LENGTH + 1) else tail) ?: return null
        val built = try {
            Locale.Builder().setLanguage(language).setRegion(region).setVariant(variant).setScript(script)
                .also { builder -> extensions.forEach { (key, value) -> builder.setExtension(key, value) } }
                .build()
        } catch (_: IllformedLocaleException) {
            // A variant the JDK maps to an extension itself, which BCP 47 does not allow: `ja_JP_JP`, `th_TH_TH`.
            Locale.of(language, region, variant)
        }
        return built.takeIf { it.toString() == text }
    }

    /**
     * The extensions of a tag's extension part, `u-ca-buddhist-x-foo`, by singleton, private use (`x`) holding the
     * rest; null when the part does not start with a singleton.
     */
    private fun extensionsOf(extensions: String): List<Pair<Char, String>>? {
        val found = mutableListOf<Pair<Char, MutableList<String>>>()
        for (subtag in extensions.split('-').filter { it.isNotEmpty() }) {
            val current = found.lastOrNull()
            when {
                subtag.length == 1 && current?.first != Locale.PRIVATE_USE_EXTENSION -> found += subtag[0] to mutableListOf()
                current == null -> return null
                else -> current.second += subtag
            }
        }
        return found.map { (key, values) -> key to values.joinToString("-") }
    }
}
