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
package me.ahoo.wow.naming

import me.ahoo.wow.infra.Decorator

/**
 * Naming Converter.
 *
 * @author ahoo wang
 */
interface NamingConverter {
    fun convert(phrase: String): String

    companion object {
        @JvmField
        val PASCAL_TO_SNAKE: NamingConverter = SimpleNamingConverter(PascalCaseStrategy, SnakeCaseStrategy)

        fun String.pascalToSnake(): String {
            return PASCAL_TO_SNAKE.convert(this)
        }
    }
}

data class SimpleNamingConverter(
    private val tokenizer: Tokenizer,
    private val namingStrategy: NamingStrategy
) : NamingConverter {
    override fun convert(phrase: String): String {
        val words = tokenizer.segment(phrase)
        return namingStrategy.transform(words)
    }
}

data class IgnorePrefixNamingConverter(val prefix: String) : NamingConverter {
    override fun convert(phrase: String): String {
        return phrase.removePrefix(prefix)
    }
}

data class AppendPrefixNamingConverter(val prefix: String) : NamingConverter {
    override fun convert(phrase: String): String {
        return prefix + phrase
    }
}

data class IgnoreSuffixNamingConverter(val suffix: String) : NamingConverter {
    override fun convert(phrase: String): String {
        return phrase.removeSuffix(suffix)
    }
}

data class AppendSuffixNamingConverter(val suffix: String) : NamingConverter {
    override fun convert(phrase: String): String {
        return phrase + suffix
    }
}

data class CompositeNamingConverter(
    override val delegate: NamingConverter,
    private val tokenizer: Tokenizer,
    private val converter: NamingStrategy
) : NamingConverter, Decorator<NamingConverter> {
    override fun convert(phrase: String): String {
        val convertedPhrase = delegate.convert(phrase)
        val words = tokenizer.segment(convertedPhrase)
        return converter.transform(words)
    }
}
