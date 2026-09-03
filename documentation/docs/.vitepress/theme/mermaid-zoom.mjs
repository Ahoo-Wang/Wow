// ponytail: fixed 1–3x range; add adaptive fit/pan math only if real diagrams exceed it.
const MIN_ZOOM = 1
const MAX_ZOOM = 3
const ZOOM_STEP = 0.25

const roundZoom = (value) => Math.round(value * 100) / 100

export const clampZoom = (value) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, roundZoom(value)))

export const stepZoom = (value, direction) => clampZoom(value + ZOOM_STEP * direction)

export const cycleFocus = (index, size, reverse) => {
    if (size <= 0) return -1
    if (index < 0) return reverse ? size - 1 : 0
    return (index + (reverse ? -1 : 1) + size) % size
}
