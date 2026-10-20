import assert from 'node:assert/strict'
import {existsSync, readFileSync} from 'node:fs'
import {test} from 'node:test'
import {EXPIRES, LIMIT, REDIRECTS, htmlFile, redirectEntries} from '../docs/.vitepress/redirects.mjs'

// The redirect table of the 9.x URLs (docs/.vitepress/redirects.mjs): bounded, expiring, from paths that are no
// page to paths that are one, and written into the built site. Run after `pnpm docs:build`.

const docs = new URL('../docs/', import.meta.url)
const dist = (path) => new URL(`../docs/.vitepress/dist/${path}`, import.meta.url)

/** The source page of a site path, or undefined: `/zh/x` is `zh/x.md`, `/x` is `en/x.md`, `x/` is `x/index.md`. */
const source = (path) => {
    const bare = path.split('#')[0]
    const local = bare.startsWith('/zh/') || bare === '/zh' ? bare.slice(1) : `en${bare}`
    const file = local.endsWith('/') ? `${local}index.md` : `${local}.md`
    return existsSync(new URL(file, docs)) ? file : undefined
}

const minor = (version) => version.split(/[.-]/).slice(0, 2).map(Number)

test('the redirect table has not outlived its version', () => {
    const version = /^version=(.+)$/m.exec(readFileSync(new URL('../../gradle.properties', import.meta.url), 'utf8'))[1]
    const [major, minorOf] = minor(version)
    const [expiresMajor, expiresMinor] = minor(EXPIRES)
    assert.ok(
        major < expiresMajor || (major === expiresMajor && minorOf < expiresMinor),
        `The project is at ${version}: delete docs/.vitepress/redirects.mjs, its use in config.mts and this test (they expire in ${EXPIRES}).`,
    )
})

test('the redirect table stays bounded', () => {
    assert.ok(Object.keys(REDIRECTS).length <= LIMIT, `More than ${LIMIT} redirects`)
})

test('each redirect leaves a path that is no page for one that is', () => {
    for (const [from, to] of redirectEntries()) {
        assert.equal(source(from), undefined, `${from} is a page again: remove its redirect`)
        assert.ok(source(to), `${from} redirects to ${to}, which is no page`)
    }
})

test('the built site holds a redirect page at each old path', () => {
    for (const [from, to] of redirectEntries()) {
        const file = dist(htmlFile(from))
        assert.ok(existsSync(file), `Missing redirect page ${htmlFile(from)}`)
        assert.ok(readFileSync(file, 'utf8').includes(`url=${to.endsWith('/') ? to : `${to}.html`}"`), `${from} does not send to ${to}`)
    }
})
