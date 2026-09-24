import assert from 'node:assert/strict'
import {existsSync, readFileSync, readdirSync} from 'node:fs'
import {test} from 'node:test'

// Pages link to stories with `/storybook/?path=/docs/<id>` or `/story/<id>`.
// VitePress's dead-link check skips `/storybook/`, because Storybook is built
// into dist after VitePress (`pnpm storybook:build`); this is the check instead.
const docs = new URL('../docs/', import.meta.url)
const dist = (path) => new URL(`../docs/.vitepress/dist/${path}`, import.meta.url)
const LINK = /\/storybook\/\?path=\/(docs|story)\/([^)\s"'<>#&]+)/g

const pages = readdirSync(docs, {recursive: true})
    .filter((file) => file.endsWith('.md') && !file.startsWith('.vitepress'))
    .map((file) => file.split('\\').join('/'))
const links = pages.flatMap((page) =>
    [...readFileSync(new URL(page, docs), 'utf8').matchAll(LINK)].map(([, kind, id]) => ({
        page,
        kind,
        id: decodeURIComponent(id),
    })),
)

test('documentation pages link to stories', () => {
    assert.ok(links.length > 0, 'No /storybook/?path= links found; the pattern or the pages changed')
})

test('every story link resolves in the built Storybook index', () => {
    const index = dist('storybook/index.json')
    assert.ok(existsSync(index), 'Storybook is not built into dist; run `pnpm --filter documentation storybook:build`')
    const {entries} = JSON.parse(readFileSync(index, 'utf8'))
    for (const {page, kind, id} of links) {
        const entry = entries[id]
        assert.ok(entry, `${page}: /storybook/?path=/${kind}/${id} is not in Storybook's index.json`)
        assert.equal(entry.type, kind === 'docs' ? 'docs' : 'story', `${page}: ${id} is not a ${kind} entry`)
    }
})

test('story links open outside the VitePress router', () => {
    for (const {page, kind, id} of links) {
        // `rewrites` serves en/ at the site root.
        const html = readFileSync(dist(page.replace(/^en\//, '').replace(/\.md$/, '.html')), 'utf8')
        assert.ok(
            html.includes(`href="/storybook/?path=/${kind}/${id}" target="_blank"`),
            `${page}: the link to ${id} does not open in a new tab`,
        )
    }
})

test('the sitemap lists Storybook', () => {
    assert.ok(readFileSync(dist('sitemap.xml'), 'utf8').includes('<loc>https://wow.ahoo.me/storybook/</loc>'))
})
