import assert from 'node:assert/strict'
import {test} from 'node:test'
import {IMPORT_SOURCES, blocks, built, check, importProblems, installedPackages, skipped} from './typescript-samples.mjs'

// Every TypeScript sample on the TypeScript pages and in the package READMEs
// compiles against the built packages; see
// typescript-samples.mjs for the directives a page uses to steer a sample.

test('TypeScript samples compile against the built packages', {timeout: 300_000}, async () => {
    for (const pkg of ['wow-client', 'wow-react', 'wow-generator', 'wow-view-engine', 'wow-view-store'])
        assert.ok(built(pkg), `typescript/${pkg} is not built; run \`pnpm --filter documentation^... build\``)
    const {samples, projects, failures} = await check()
    assert.ok(samples.length > 100, `Only ${samples.length} samples found; the sources or the fence pattern changed`)
    // The quick starts compile with the tsconfig.json they show (review 2026-09 round 2, P0-2),
    // and so does the view engine's getting started.
    for (const guide of ['quick-start', 'view-engine-getting-started'])
        for (const locale of ['en', 'zh'])
            assert.ok(
                projects.some(({page}) => page === `documentation/docs/${locale}/guide/typescript/${guide}.md`),
                `the ${locale} ${guide} page is no longer checked as a project; it lost its <!-- typecheck: file=tsconfig.json -->`,
            )
    assert.deepEqual(failures, [], `${failures.length} diagnostics:\n${failures.join('\n')}`)
})

test("the wow-client and wow-generator Skills import only names their entries export", {timeout: 300_000}, () => {
    assert.ok(IMPORT_SOURCES.length >= 4, `Only ${IMPORT_SOURCES.length} Skill pages found`)
    const {imports, problems} = importProblems()
    assert.ok(imports > 100, `Only ${imports} imported names found; the sources or the import parsing changed`)
    assert.deepEqual(problems, [], `${problems.length} problems:\n${problems.join('\n')}`)
})

test('skipped samples say why', () => {
    for (const {page, line, reason} of skipped()) assert.ok(reason.length >= 8, `${page}:${line}: say why the sample is skipped`)
})

test('install commands name the packages a project page installs', () => {
    const page = [
        '```bash',
        'pnpm add @ahoo-wang/fetcher \\',
        '  @ahoo-wang/wow-client@next',
        'pnpm add -D typescript @types/node@^24',
        'npm install --save-exact left-pad',
        'pnpm exec wow-generator generate -i openapi.json',
        '```',
    ].join('\n')
    assert.deepEqual(
        [...installedPackages(blocks(page))].sort(),
        ['@ahoo-wang/fetcher', '@ahoo-wang/wow-client', '@types/node', 'left-pad', 'typescript'],
    )
})
