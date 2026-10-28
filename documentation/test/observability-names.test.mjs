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
const REPORTERS = ['edge/observability', 'host/spring-boot', 'backends/kafka', 'platform/analytics/catalog/server']
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

// The other way: every metric of the catalog is on the page, as the catalog declares it: its type, exactly its
// tags, and every value of a tag whose values the framework names. The catalog is held to its snapshot
// (edge/observability MetricNamingTest), and the analytics catalog server's own gauges to its constants.
const catalog = JSON.parse(
    readFileSync(join(repository, 'edge/observability/src/test/resources/metrics.json'), 'utf8'),
)
const analytics = readFileSync(
    join(
        repository,
        'platform/analytics/catalog/server/src/main/kotlin/me/ahoo/wow/analytics/catalog/server/AnalyticsCatalogConfiguration.kt',
    ),
    'utf8',
)
const analyticsGauges = [...analytics.matchAll(/const val DATASETS_\w+: String = "(wow\.[^"]+)"/g)].map(([, name]) => ({
    name,
    type: 'gauge',
    tags: [],
    values: {},
}))
const TYPES = {timer: 'Timer', counter: 'Counter', gauge: 'Gauge'}

for (const locale of ['en', 'zh']) {
    const page = join(repository, `documentation/docs/${locale}/reference/observability.md`)
    test(`every metric of the catalog is on the ${locale} observability reference, as declared`, {skip: !existsSync(page)}, () => {
        const metrics = [...catalog, ...analyticsGauges]
        assert.ok(catalog.length > 30, `Only ${catalog.length} metrics found; the snapshot's shape changed`)
        assert.equal(analyticsGauges.length, 2, "The analytics catalog server's gauges changed")
        const rows = new Map(
            readFileSync(page, 'utf8')
                .split('\n')
                .filter((line) => line.startsWith('| `wow.'))
                .map((line) => {
                    const cells = line.split(' | ').map((cell) => cell.replace(/^\| |\s*\|$/g, ''))
                    return [cells[0].replaceAll('`', ''), {cells, line}]
                }),
        )
        const wrong = []
        for (const metric of metrics) {
            const row = rows.get(metric.name)
            if (!row) {
                wrong.push(`${metric.name}: not on the page`)
                continue
            }
            const [, type, tags] = row.cells
            if (type !== TYPES[metric.type]) wrong.push(`${metric.name}: ${type}, not ${TYPES[metric.type]}`)
            const listed = [...(tags ?? '').matchAll(/`([^`]+)`/g)].map(([, tag]) => tag)
            if (listed.join(',') !== metric.tags.join(','))
                wrong.push(`${metric.name}: tags ${listed.join(', ')}, not ${metric.tags.join(', ')}`)
            for (const [tag, values] of Object.entries(metric.values))
                for (const value of values)
                    if (!row.line.includes(`\`${value}\``) && !row.line.includes(`\`${tag}=${value}\``))
                        wrong.push(`${metric.name}: no \`${value}\` of ${tag}`)
        }
        assert.deepEqual(wrong, [], 'The page differs from the catalog')
    })
}
