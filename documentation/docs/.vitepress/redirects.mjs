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

import {mkdirSync, writeFileSync} from 'node:fs'
import {dirname, join} from 'node:path'

// The 9.x URLs that have a direct successor on this site. The build writes a static page at each of them that sends
// the reader on; every other 9.x URL reaches the 404 page, which points to the 9.x pages under /9.x/ (built from the
// `v9` branch). The table is bounded and expires: documentation/test/redirects.test.mjs fails once the project's
// version reaches EXPIRES, and the table goes then. Each entry holds for both locales (`/zh` before both sides).
export const EXPIRES = '10.1.0'

/** The most entries the table may hold. */
export const LIMIT = 50

/** Old path → path of its successor; both without `.html`, a trailing `/` for a directory's index. */
export const REDIRECTS = {
    '/guide/': '/guide/v10/',
    '/guide/introduction': '/',
    '/guide/getting-started': '/guide/v10/quickstart',
    '/guide/existing-project': '/guide/v10/',
    '/guide/core-concepts': '/guide/v10/programming-model',
    '/guide/best-practices': '/guide/v10/deployment',
    '/guide/configuration': '/guide/v10/configuration',
    '/guide/projection': '/guide/v10/programming-model',
    '/guide/query': '/guide/v10/query',
    '/guide/test-suite': '/guide/testing',
    '/guide/application-testing': '/guide/testing',
    '/guide/open-api': '/guide/v10/http-api',
    '/guide/domain/': '/guide/v10/programming-model',
    '/guide/domain/aggregate': '/guide/v10/programming-model',
    '/guide/domain/event-evolution': '/guide/v10/programming-model',
    '/guide/domain/lifecycle': '/guide/v10/programming-model',
    '/guide/event/': '/guide/v10/programming-model',
    '/guide/event/saga': '/guide/v10/programming-model',
    '/guide/event/processor': '/guide/v10/programming-model',
    '/guide/command/': '/guide/v10/http-api',
    '/guide/command/definition': '/guide/v10/programming-model',
    '/guide/command/sending': '/guide/v10/http-api',
    '/guide/command/completion': '/guide/v10/http-api',
    '/guide/query/filter-expression': '/guide/v10/query',
    '/guide/query/snapshot-query': '/guide/v10/query',
    '/guide/query/aggregation-query': '/guide/v10/query',
    '/guide/query/data-query': '/guide/v10/query',
    '/guide/advanced/compiler': '/guide/v10/generated-code',
    '/guide/advanced/schema': '/guide/v10/generated-code',
    '/guide/advanced/documentation-governance': '/contribute/documentation',
    '/guide/extensions/spring-boot-starter': '/guide/v10/',
    '/guide/extensions/mongo': '/guide/v10/deployment',
    '/guide/extensions/kafka': '/guide/v10/deployment',
    '/guide/extensions/view-store': '/guide/typescript/view-engine-storage',
    '/reference/config/core': '/guide/v10/configuration',
    '/reference/config/infrastructure': '/guide/v10/configuration',
    '/reference/config/observability': '/guide/v10/configuration',
    '/onboarding/': '/',
}

/** Every entry in both locales: `[from, to]`. */
export const redirectEntries = () =>
    Object.entries(REDIRECTS).flatMap(([from, to]) => [[from, to], [`/zh${from}`, to === '/' ? '/zh/' : `/zh${to}`]])

/** The built file of a path: `x` is `x.html`, `x/` is `x/index.html`. */
export const htmlFile = (path) => (path.endsWith('/') ? `${path}index.html` : `${path}.html`).slice(1)

const stub = (to) => {
    const href = to.endsWith('/') ? to : `${to}.html`
    return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Moved</title>
<link rel="canonical" href="https://wow.ahoo.me${href}">
<meta name="robots" content="noindex">
<meta http-equiv="refresh" content="0; url=${href}">
<script>location.replace(${JSON.stringify(href)} + location.hash)</script>
</head>
<body><p>This page moved to <a href="${href}">${href}</a>.</p></body>
</html>
`
}

/** Writes a redirect page into the built site for each entry. */
export const writeRedirects = (outDir) => {
    for (const [from, to] of redirectEntries()) {
        const file = join(outDir, htmlFile(from))
        mkdirSync(dirname(file), {recursive: true})
        writeFileSync(file, stub(to))
    }
}
