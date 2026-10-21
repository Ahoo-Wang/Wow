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
    '/guide/': '/start/quickstart',
    '/guide/introduction': '/',
    '/guide/getting-started': '/start/quickstart',
    '/guide/existing-project': '/start/existing-project',
    '/guide/core-concepts': '/concepts/',
    '/guide/best-practices': '/operate/topology',
    '/guide/configuration': '/reference/configuration',
    '/guide/projection': '/guide/projections',
    '/guide/query': '/guide/queries',
    '/guide/test-suite': '/guide/testing',
    '/guide/application-testing': '/guide/testing',
    '/guide/open-api': '/guide/openapi',
    '/guide/domain/': '/guide/aggregates',
    '/guide/domain/aggregate': '/guide/aggregates',
    '/guide/domain/event-evolution': '/guide/event-evolution',
    '/guide/domain/lifecycle': '/guide/aggregates',
    '/guide/event/': '/guide/sagas',
    '/guide/event/saga': '/guide/sagas',
    '/guide/event/processor': '/guide/projections',
    '/guide/command/': '/guide/http',
    '/guide/command/definition': '/guide/aggregates',
    '/guide/command/sending': '/guide/http',
    '/guide/command/completion': '/concepts/waits',
    '/guide/query/filter-expression': '/reference/query-language',
    '/guide/query/snapshot-query': '/guide/queries',
    '/guide/query/aggregation-query': '/reference/query-language',
    '/guide/query/data-query': '/guide/datasets',
    '/guide/advanced/compiler': '/guide/generated-code',
    '/guide/advanced/schema': '/guide/manifest',
    '/guide/advanced/documentation-governance': '/contribute/documentation',
    '/guide/extensions/spring-boot-starter': '/guide/spring-boot',
    '/guide/extensions/mongo': '/operate/topology',
    '/guide/extensions/kafka': '/operate/kafka',
    '/guide/extensions/view-store': '/typescript/view-engine/storage',
    '/reference/config/core': '/reference/configuration',
    '/reference/config/infrastructure': '/reference/configuration',
    '/reference/config/observability': '/reference/configuration',
    '/guide/advanced/architecture': '/concepts/',
    '/guide/domain/event-sourcing': '/concepts/event-log',
    '/guide/domain/snapshot': '/concepts/read-models',
    '/guide/extensions/cosec': '/concepts/identity',
    '/guide/typescript/view-engine': '/typescript/view-engine/',
    '/guide/typescript/': '/typescript/',
    '/guide/typescript/quick-start': '/typescript/generate',
    '/guide/typescript/generated-client': '/typescript/openapi-client',
    '/guide/typescript/regenerate-in-ci': '/typescript/generate',
    '/workbench/': '/data/workbench',
    '/guide/event/compensation': '/operate/ops-console',
    '/guide/migration': '/migrate/',
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
