<!--
 Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<script setup lang="ts">
import {nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useData} from 'vitepress'
import type svgPanZoom from 'svg-pan-zoom'
import {cycleFocus} from './mermaid-zoom.mjs'

type PanZoomInstance = ReturnType<typeof svgPanZoom>

const props = withDefaults(defineProps<{
    graph: string
    id: string
    class?: string
}>(), {
    class: 'mermaid',
})
const {isDark} = useData()
const svg = ref('')
const viewer = ref<HTMLElement>()
const expandButton = ref<HTMLButtonElement>()
const isExpanded = ref(false)
let panZoom: PanZoomInstance | undefined
let previousBodyOverflow = ''

const getSvg = () => viewer.value?.querySelector<SVGSVGElement>('.mermaid-content > svg')

const destroyPanZoom = () => {
    panZoom?.destroy()
    panZoom = undefined
}

const fitPanZoom = () => {
    panZoom?.resize().fit().center()
}

const initializePanZoom = async () => {
    await nextTick()
    const svgElement = getSvg()
    if (!svgElement) return

    const {default: createPanZoom} = await import('svg-pan-zoom')
    panZoom = createPanZoom(svgElement, {
        controlIconsEnabled: false,
        dblClickZoomEnabled: false,
        fit: true,
        maxZoom: 10,
        minZoom: 0.1,
        mouseWheelZoomEnabled: false,
        panEnabled: isExpanded.value,
        preventMouseEventsDefault: false,
        zoomEnabled: true,
    })
    if (isExpanded.value) fitPanZoom()
}

const renderChart = async () => {
    destroyPanZoom()
    const {default: mermaid} = await import('mermaid')
    mermaid.initialize({
        securityLevel: 'loose',
        startOnLoad: false,
        theme: isDark.value ? 'dark' : 'default',
    })
    svg.value = (await mermaid.render(props.id, decodeURIComponent(props.graph))).svg
    await initializePanZoom()
}

const resetZoom = () => {
    if (isExpanded.value) fitPanZoom()
    else panZoom?.reset()
}

const handleWheel = (event: WheelEvent) => {
    if (!panZoom || (!event.ctrlKey && !event.metaKey) || event.deltaY === 0) return
    event.preventDefault()
    if (event.deltaY < 0) panZoom.zoomIn()
    else panZoom.zoomOut()
}

const openExpanded = () => {
    isExpanded.value = true
}

const closeExpanded = () => {
    isExpanded.value = false
}

const focusableControls = () => Array.from(
    viewer.value?.querySelectorAll<HTMLElement>(
        'button, a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
    ) ?? []
)

const handleKeydown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
        event.preventDefault()
        closeExpanded()
        return
    }
    if (event.key !== 'Tab' || !isExpanded.value) return
    const controls = focusableControls()
    if (controls.length === 0) return
    const currentIndex = controls.indexOf(document.activeElement as HTMLElement)
    const atStart = currentIndex <= 0
    const atEnd = currentIndex === controls.length - 1
    if ((event.shiftKey && atStart) || (!event.shiftKey && (atEnd || currentIndex < 0))) {
        event.preventDefault()
        controls[cycleFocus(currentIndex, controls.length, event.shiftKey)]?.focus()
    }
}

watch(isExpanded, async (expanded) => {
    if (typeof document === 'undefined') return
    if (expanded) {
        previousBodyOverflow = document.body.style.overflow
        document.body.style.overflow = 'hidden'
        await nextTick()
        panZoom?.enablePan()
        fitPanZoom()
        const firstControl = focusableControls()[0]
        if (firstControl) firstControl.focus()
        else viewer.value?.focus()
    } else {
        await nextTick()
        panZoom?.disablePan()
        panZoom?.reset()
        document.body.style.overflow = previousBodyOverflow
        expandButton.value?.focus()
    }
})

onMounted(() => void renderChart())
watch(isDark, () => void renderChart())
onBeforeUnmount(() => {
    destroyPanZoom()
    if (isExpanded.value && typeof document !== 'undefined') {
        document.body.style.overflow = previousBodyOverflow
    }
})
</script>

<template>
    <div
        ref="viewer"
        :class="['mermaid-viewer', props.class, {'mermaid-viewer--expanded': isExpanded}]"
        :tabindex="isExpanded ? 0 : undefined"
        :role="isExpanded ? 'dialog' : undefined"
        :aria-modal="isExpanded ? 'true' : undefined"
        aria-label="Mermaid diagram"
        @wheel="handleWheel"
        @keydown="handleKeydown"
        @click.self="closeExpanded"
    >
        <div class="mermaid-viewport" @click.self="closeExpanded">
            <div class="mermaid-content" v-html="svg"></div>
        </div>
        <div class="mermaid-toolbar" role="toolbar" aria-label="Mermaid diagram controls">
            <button type="button" aria-label="Zoom in" title="Zoom in" @click="panZoom?.zoomIn()">+</button>
            <button type="button" aria-label="Zoom out" title="Zoom out" @click="panZoom?.zoomOut()">−</button>
            <button type="button" aria-label="Reset zoom" title="Reset zoom" @click="resetZoom">↺</button>
            <button
                v-if="!isExpanded"
                ref="expandButton"
                type="button"
                aria-label="Expand diagram"
                title="Expand diagram"
                @click="openExpanded"
            >
                ⛶
            </button>
            <button
                v-else
                type="button"
                aria-label="Close expanded diagram"
                title="Close expanded diagram"
                @click="closeExpanded"
            >
                ×
            </button>
        </div>
    </div>
</template>

<style>
.mermaid-viewer {
    position: relative;
}

.mermaid-viewport {
    overflow: auto;
}

.mermaid-content {
    display: flex;
    justify-content: center;
    min-width: 100%;
}

.mermaid-content > svg {
    display: block;
    width: 100%;
    height: auto;
    max-width: 100%;
    flex: 0 0 auto;
}

.mermaid-toolbar {
    position: absolute;
    top: 0.75rem;
    right: 0.75rem;
    z-index: 1;
    display: flex;
    gap: 0.25rem;
    padding: 0.25rem;
    border: 1px solid var(--vp-c-divider);
    border-radius: 0.5rem;
    background: var(--vp-c-bg-soft);
    box-shadow: var(--vp-shadow-2);
    opacity: 0;
    transition: opacity 0.2s ease;
}

.mermaid-viewer:hover .mermaid-toolbar,
.mermaid-viewer:focus-within .mermaid-toolbar,
.mermaid-viewer--expanded .mermaid-toolbar {
    opacity: 1;
}

.mermaid-toolbar button {
    display: inline-flex;
    width: 2rem;
    height: 2rem;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 0.35rem;
    background: transparent;
    color: var(--vp-c-text-1);
    cursor: pointer;
    font-size: 1.1rem;
}

.mermaid-toolbar button:hover,
.mermaid-toolbar button:focus-visible {
    background: var(--vp-c-default-soft);
}

.mermaid-toolbar button:focus-visible {
    outline: 2px solid var(--vp-c-brand-1);
    outline-offset: 2px;
}

.mermaid-viewer--expanded {
    position: fixed;
    inset: 0;
    z-index: 1000;
    display: flex;
    flex-direction: column;
    padding: 4rem 1rem 1rem;
    background: var(--vp-c-bg);
}

.mermaid-viewer--expanded .mermaid-viewport {
    flex: 1;
    min-height: 0;
}

.mermaid-viewer--expanded .mermaid-content {
    width: 100%;
    height: 100%;
    min-width: 0;
    min-height: 0;
}

.mermaid-viewer--expanded .mermaid-content > svg {
    width: 100% !important;
    height: 100% !important;
    max-width: none !important;
}

@media (prefers-reduced-motion: reduce) {
    .mermaid-toolbar {
        transition: none;
    }
}

@media (any-hover: none) {
    .mermaid-toolbar {
        opacity: 1;
    }
}
</style>
