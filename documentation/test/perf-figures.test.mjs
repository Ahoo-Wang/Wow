import assert from 'node:assert/strict'
import {readdirSync, readFileSync, existsSync} from 'node:fs'
import {join} from 'node:path'
import {test} from 'node:test'
import {FIGURES, cpuModel, figureHtml, figuresOf} from '../docs/.vitepress/theme/perf-figures.mjs'

const REPORTS = join(import.meta.dirname, '../../test/benchmarks/results/reports')
const committed = Object.fromEntries(
    readdirSync(REPORTS)
        .filter(name => existsSync(join(REPORTS, name, 'headline.json')))
        .map(name => [name, JSON.parse(readFileSync(join(REPORTS, name, 'headline.json'), 'utf8'))]),
)

const step = (clients, value, p99Ms, failedShare = 0) => ({clients, value, p50Ms: 1, p99Ms, failedShare, refused: 0})
const sweep = (store, scenario, extra = {}) => ({
    id: `sweep.${store}.1.${scenario.toLowerCase()}-sweep`,
    kind: 'sweep',
    store,
    scenario,
    keys: 'uniform',
    commit: 'abc1234567',
    cpu: 'Apple M5 Max, 18 cores, 64 GB',
    jdk: '25.0.5+8',
    sustainable: {value: 900, clients: 16},
    steps: [step(1, 100, 3), step(16, 900, 40), step(256, 1200, 400, 0.3)],
    ...extra,
})
const synthetic = figures => ({'m5max-snapshot': {figures}})
const sweeps = [sweep('postgres', 'E1'), sweep('mongo', 'E1'), sweep('postgres', 'E3'), sweep('mongo', 'E3')]

test('every figure is drawn from the committed headlines, in both languages', () => {
    for (const name of Object.keys(FIGURES))
        for (const locale of ['en', 'zh']) {
            const html = figureHtml(name, locale, committed)
            assert.match(html, /^<figure class="perf-figure"><.*<\/figure>$/s, `${name} ${locale}`)
            assert.match(html, /<svg class="perf-chart[ "]/)
            assert.match(html, /<table>/)
        }
})

test("a figure's caption names its build, CPU and JDK, in the page's language", () => {
    const html = figureHtml('m5max-sweep', 'en', synthetic(sweeps))
    assert.match(html, /<figcaption>Build abc1234567, Apple M5 Max, 18 cores, 64 GB, JDK 25\.0\.5\+8\.<\/figcaption>/)
    assert.match(figureHtml('m5max-sweep', 'zh', synthetic(sweeps)), /<figcaption>构建 abc1234567，/)
    assert.match(figureHtml('actions-axon', 'en', committed), /<figcaption>Build \w+, EPYC 9V74, JDK /)
})

test('a CPU model is named as a reader names it', () => {
    assert.equal(cpuModel('AMD EPYC 9V74 80-Core Processor'), 'EPYC 9V74')
    assert.equal(cpuModel('Intel(R) Xeon(R) 6975P-C'), 'Xeon 6975P-C')
    assert.equal(cpuModel('INTEL(R) XEON(R) PLATINUM 8573C'), 'Xeon 8573C')
    assert.equal(cpuModel('Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz'), 'Xeon 8370C')
    assert.equal(cpuModel('Apple M5 Max, 18 cores, 64 GB'), 'Apple M5 Max, 18 cores, 64 GB')
})

test('a sweep draws a line a store, marks and names its sustainable step, the 200 ms line and its refused steps hollow', () => {
    const html = figureHtml('m5max-sweep', 'en', synthetic(sweeps))
    assert.equal(html.match(/<polyline class="perf-line perf-series-1"/g).length, 2)
    assert.equal(html.match(/<polyline class="perf-line perf-series-2"/g).length, 2)
    assert.equal(html.match(/<circle class="perf-dot perf-best /g).length, 4)
    assert.equal(html.match(/sustainable: PostgreSQL 900\.0\/s · MongoDB 900\.0\/s</g).length, 2)
    assert.equal(html.match(/>200 ms</g).length, 2)
    assert.equal(html.match(/perf-dot perf-series-\d perf-hollow/g).length, 4)
    assert.match(html, /<title>PostgreSQL, 256 clients: 1,200\.0\/s, p99 400 ms, 30\.0% refused<\/title>/)
    assert.equal(html.match(/<tr><td>/g).length, 12, 'one table row a step')
})

test('a ratio draws each case in its A/A band, a level case hollow and a lower bound with ≥', () => {
    const ratio = (measure, extra) => ({
        id: `axon.m5max-axon-peak-e1.e1.${measure}`,
        kind: 'ratio',
        measure,
        suite: 'm5max-axon-peak-e1',
        scenario: 'E1',
        keys: 'uniform',
        lo: 1.1,
        hi: 1.3,
        bound: '',
        band: 0.05,
        verdict: 'wow higher',
        comparable: true,
        wow: [200, 210],
        axon: [100, 110],
        commit: 'abc1234567',
        cpu: 'Apple M5 Max, 18 cores, 64 GB',
        jdk: '25.0.5+8',
        ...extra,
    })
    const headlines = {
        'm5max-axon': {
            figures: [
                ratio('peak', {bound: '≥', id: 'p'}),
                ratio('sustainable', {verdict: 'level (within the A/A spread)', id: 's'}),
                ratio('peak', {comparable: false, id: 'x'}),
            ],
        },
    }
    const original = FIGURES['m5max-axon'].rows
    FIGURES['m5max-axon'].rows = ['p', 's', 'x']
    try {
        const html = figureHtml('m5max-axon', 'en', headlines)
        assert.match(html, /<svg class="perf-chart perf-wide"[\s\S]*<svg class="perf-chart perf-narrow"/, 'a wide and a narrow layout')
        assert.equal(html.match(/>E1, sustainable</g).length, 3, "each layout labels the case, and so does the table")
        assert.match(html, />≥ 1\.1–1\.3</)
        assert.equal(html.match(/perf-dot perf-series-1 perf-hollow/g).length, 4, 'the level case, in each layout')
        assert.equal(html.match(/<rect class="perf-band/g).length, 4, 'no band for a case not compared, in each layout')
        assert.match(html, />not compared: a data check failed</)
        assert.match(html, /<td>E1, peak<\/td><td>≥ 1\.1–1\.3<\/td><td>±5\.0%<\/td><td>wow higher<\/td><td>210\.0<\/td><td>110\.0<\/td>/)
    } finally {
        FIGURES['m5max-axon'].rows = original
    }
})

test('a figure the report does not hold, or one that mixes builds, CPUs or JDKs, stops the build', () => {
    assert.throws(() => figureHtml('no-such', 'en', committed), /no performance figure named no-such/)
    assert.throws(() => figureHtml('m5max-sweep', 'fr', synthetic(sweeps)), /no words for the locale fr/)
    assert.throws(() => figureHtml('m5max-sweep', 'en', {}), /no headline for the report m5max-snapshot/)
    assert.throws(() => figureHtml('m5max-sweep', 'en', synthetic(sweeps.slice(1))), /holds no figure sweep\.postgres\.1\.e1-sweep/)
    for (const [field, value] of [['commit', 'def7654321'], ['cpu', 'AMD EPYC 7763'], ['jdk', '21.0.9+10']]) {
        const mixed = [sweep('postgres', 'E1', {[field]: value}), ...sweeps.slice(1)]
        assert.throws(() => figureHtml('m5max-sweep', 'en', synthetic(mixed)), new RegExp(`differ in ${field}`))
    }
    assert.deepEqual(figuresOf(synthetic(sweeps), 'm5max-snapshot', [sweeps[1].id], 'n'), [sweeps[1]])
})
