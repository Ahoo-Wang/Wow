import assert from 'node:assert/strict'
import {test} from 'node:test'
import {built, check, skipped} from './typescript-samples.mjs'

// Every TypeScript sample on the TypeScript pages and in the package READMEs
// compiles against the built packages; see
// typescript-samples.mjs for the directives a page uses to steer a sample.

test('TypeScript samples compile against the built packages', {timeout: 300_000}, async () => {
    for (const pkg of ['wow-client', 'wow-react', 'wow-generator', 'wow-view-engine'])
        assert.ok(built(pkg), `typescript/${pkg} is not built; run \`pnpm --filter documentation^... build\``)
    const {samples, failures} = await check()
    assert.ok(samples.length > 100, `Only ${samples.length} samples found; the sources or the fence pattern changed`)
    assert.deepEqual(failures, [], `${failures.length} diagnostics:\n${failures.join('\n')}`)
})

test('skipped samples say why', () => {
    for (const {page, line, reason} of skipped()) assert.ok(reason.length >= 8, `${page}:${line}: say why the sample is skipped`)
})
