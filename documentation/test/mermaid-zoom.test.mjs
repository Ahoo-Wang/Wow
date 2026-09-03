import assert from 'node:assert/strict'
import {test} from 'node:test'
import {clampZoom, cycleFocus, stepZoom} from '../docs/.vitepress/theme/mermaid-zoom.mjs'

test('Mermaid zoom stays within the supported range', () => {
    assert.equal(clampZoom(0.5), 1)
    assert.equal(clampZoom(3.5), 3)
    assert.equal(stepZoom(1, 1), 1.25)
    assert.equal(stepZoom(3, -1), 2.75)
})

test('Mermaid expanded controls cycle focus at either edge', () => {
    assert.equal(cycleFocus(0, 4, true), 3)
    assert.equal(cycleFocus(3, 4, false), 0)
    assert.equal(cycleFocus(-1, 4, false), 0)
})
