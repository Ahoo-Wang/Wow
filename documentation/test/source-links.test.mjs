import assert from 'node:assert/strict'
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
