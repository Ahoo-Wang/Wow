import assert from 'node:assert/strict'
import {test} from 'node:test'
import {cycleFocus} from '../docs/.vitepress/theme/mermaid-zoom.mjs'

test('Mermaid expanded controls cycle focus at either edge', () => {
    assert.equal(cycleFocus(0, 4, true), 3)
    assert.equal(cycleFocus(3, 4, false), 0)
    assert.equal(cycleFocus(-1, 4, false), 0)
})
