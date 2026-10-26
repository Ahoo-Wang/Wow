import assert from 'node:assert/strict'
import {execFileSync} from 'node:child_process'
import {existsSync, readFileSync} from 'node:fs'
import {join} from 'node:path'
import {test} from 'node:test'
import {fileURLToPath} from 'node:url'

// reference/observability lists every metric, span attribute and health
// component a node reports. Each `wow.*` or `messaging.*` name its tables give
// must be a string literal in the code that reports it, so a renamed metric
// fails here instead of on a dashboard. Configuration keys (`wow.health.*`,
// `wow.observability.*`) are held to the configuration metadata elsewhere.
const repository = fileURLToPath(new URL('../../', import.meta.url))
const REPORTERS = ['edge/observability', 'host/spring-boot', 'backends/kafka']
const CONFIGURATION = /^wow\.(health|observability)\./
const NAME = /`((?:wow|messaging)\.[a-z0-9._-]*[a-z0-9_])`/g

const sources = execFileSync('git', ['ls-files', '--', ...REPORTERS.map((dir) => `${dir}/src/main/**/*.kt`)], {
    cwd: repository,
    encoding: 'utf8',
})
    .split('\n')
    .filter(Boolean)
    .map((file) => readFileSync(join(repository, file), 'utf8'))
    .join('\n')

for (const locale of ['en', 'zh']) {
    const page = join(repository, `documentation/docs/${locale}/reference/observability.md`)
    test(`every name of the ${locale} observability reference is reported by the code`, {skip: !existsSync(page)}, () => {
        const names = new Set()
        for (const line of readFileSync(page, 'utf8').split('\n').filter((line) => line.startsWith('|')))
            for (const [, name] of line.matchAll(NAME)) if (!CONFIGURATION.test(name)) names.add(name)
        assert.ok(names.size > 40, `Only ${names.size} names found; the page's tables changed`)
        const missing = [...names].filter((name) => !sources.includes(`"${name}"`))
        assert.deepEqual(missing, [], `Not a string literal in ${REPORTERS.join(', ')}: ${missing.join(', ')}`)
    })
}
