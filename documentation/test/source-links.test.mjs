import assert from 'node:assert/strict'
import {execFileSync} from 'node:child_process'
import {existsSync, readFileSync} from 'node:fs'
import {join, relative} from 'node:path'
import {test} from 'node:test'
import {fileURLToPath} from 'node:url'
import {SOURCES} from './typescript-samples.mjs'

// The TypeScript pages and the package READMEs link to the source of what they
// describe. Line anchors on `main` drift with every edit above them, so the
// links name files only: a file link breaks when the file moves, and this
// test catches that.
const repository = fileURLToPath(new URL('../../', import.meta.url))
const LINK = /https:\/\/github\.com\/Ahoo-Wang\/Wow\/(?:blob|tree)\/main\/([^)\s"'<>]+)/g

const links = SOURCES.filter(existsSync).flatMap((source) =>
    [...readFileSync(source, 'utf8').matchAll(LINK)].map(([, target]) => ({page: relative(repository, source), target})),
)

test('the pages link to source files', () => {
    assert.ok(links.length > 100, `Only ${links.length} source links found; the pattern or the pages changed`)
})

test('source links carry no line anchor', () => {
    const anchored = links.filter(({target}) => /#L\d/.test(target))
    assert.deepEqual(
        anchored.map(({page, target}) => `${page}: ${target}`),
        [],
        'Link the file without #L…: line numbers on main drift',
    )
})

test('every linked source file exists', () => {
    const missing = links.filter(({target}) => !existsSync(join(repository, decodeURIComponent(target.split('#')[0]))))
    assert.deepEqual(
        missing.map(({page, target}) => `${page}: ${target}`),
        [],
    )
})

// The V10 pages link to `v10`, the branch they describe. Every tracked file is
// checked, not only the pages above: a `v10` link breaks when its target moves
// or leaves the tree, as the pre-eShop examples did. The 9.x pages' `main`
// links wait for the documentation rewrite.
const V10_LINK = /https:\/\/github\.com\/Ahoo-Wang\/Wow\/(?:blob|tree)\/v10\/([^)\s"'<>`\]]+)/g
const v10Links = execFileSync('git', ['grep', '-l', '-E', 'github\\.com/Ahoo-Wang/Wow/(blob|tree)/v10/'], {
    cwd: repository,
    encoding: 'utf8',
})
    .split('\n')
    .filter(Boolean)
    .flatMap((page) =>
        [...readFileSync(join(repository, page), 'utf8').matchAll(V10_LINK)].map(([, target]) => ({page, target})),
    )

test('the V10 pages link to v10 sources', () => {
    assert.ok(v10Links.length > 0, 'No v10 source links found; the pattern or the pages changed')
})

test('every v10 link points into the tree', () => {
    const missing = v10Links.filter(
        ({target}) => !existsSync(join(repository, decodeURIComponent(target.split(/[#?]/)[0].replace(/[.,;:]+$/, '')))),
    )
    assert.deepEqual(
        missing.map(({page, target}) => `${page}: ${target}`),
        [],
    )
})
