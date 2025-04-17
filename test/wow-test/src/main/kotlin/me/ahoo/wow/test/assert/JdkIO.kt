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

package me.ahoo.wow.test.assert

import org.assertj.core.api.FileAssert
import org.assertj.core.api.PathAssert
import org.assertj.core.api.UriAssert
import org.assertj.core.api.UrlAssert
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Path

fun Path.assert(): PathAssert {
    return PathAssert(this)
}

fun File.assert(): FileAssert {
    return FileAssert(this)
}

fun URL.assert(): UrlAssert {
    return UrlAssert(this)
}

fun URI.assert(): UriAssert {
    return UriAssert(this)
}
