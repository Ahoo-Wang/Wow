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
import {computed, nextTick, onBeforeUnmount, onMounted, ref, type CSSProperties} from 'vue'
import {useData, withBase} from 'vitepress'

const {frontmatter, lang} = useData()
const showAward = computed(() => frontmatter.value.kaicodeAward === true)
const isChinese = computed(() => lang.value.startsWith('zh'))
const banner = ref<HTMLElement | null>(null)
const celebrating = ref(false)
let celebrationObserver: IntersectionObserver | undefined

type ConfettiStyle = CSSProperties & Record<`--confetti-${string}`, string>

const confettiPalette = ['#d3862d', '#d0011b', '#f0ad5d', '#5b8cff', '#9b5cff', '#ffcf70']
const confettiPieces = Array.from({length: 36}, (_, index) => {
    const style: ConfettiStyle = {
        '--confetti-x': `${(index * 37 + 7) % 100}%`,
        '--confetti-drift': `${(index * 53) % 150 - 75}px`,
        '--confetti-delay': `${((index * 29) % 95) / 100}s`,
        '--confetti-duration': `${2.1 + ((index * 17) % 90) / 100}s`,
        '--confetti-spin': `${540 + (index * 71) % 720}deg`,
        '--confetti-color': confettiPalette[index % confettiPalette.length],
        '--confetti-width': `${6 + index % 4}px`,
        '--confetti-height': `${10 + index % 5 * 2}px`,
    }

    return {id: index, style}
})

const observeAward = () => {
    if (!banner.value || celebrating.value) {
        return
    }
    if (!('IntersectionObserver' in window)) {
        celebrating.value = true
        return
    }
    celebrationObserver = new IntersectionObserver(([entry]) => {
        if (!entry.isIntersecting) {
            return
        }
        celebrating.value = true
        celebrationObserver?.disconnect()
    }, {threshold: 0.2})
    celebrationObserver.observe(banner.value)
}

onMounted(async () => {
    await nextTick()
    observeAward()
})

onBeforeUnmount(() => {
    celebrationObserver?.disconnect()
})

const copy = computed(() => isChinese.value
    ? {
        eyebrow: '开源工程荣誉',
        title: 'Wow 荣获 KaiCode’26 Excellent Award',
        cta: '查看官方结果',
    }
    : {
        eyebrow: 'Open-source engineering recognition',
        title: 'Wow received the KaiCode’26 Excellent Award',
        cta: 'View official results',
    })
</script>

<template>
    <section
        v-if="showAward"
        ref="banner"
        class="kaicode-award-banner"
        :class="{'is-celebrating': celebrating}"
        aria-labelledby="kaicode-award-title"
    >
        <div class="kaicode-award-banner__inner">
            <div class="kaicode-award-banner__confetti" aria-hidden="true">
                <i
                    v-for="piece in confettiPieces"
                    :key="piece.id"
                    class="kaicode-award-banner__confetti-piece"
                    :style="piece.style"
                />
            </div>
            <a
                class="kaicode-award-banner__artwork-link"
                href="https://www.kaicode.org/2026.html"
                target="_blank"
                rel="noopener noreferrer"
                :aria-label="`KaiCode’26 Excellent Award — ${copy.cta}`"
            >
                <img
                    class="kaicode-award-banner__artwork"
                    width="669"
                    height="575"
                    :src="withBase('/images/kaicode-2026-wow.svg')"
                    alt="KaiCode'26 Excellent Award"
                />
            </a>
            <div class="kaicode-award-banner__content">
                <p class="kaicode-award-banner__eyebrow">{{ copy.eyebrow }}</p>
                <h2 id="kaicode-award-title" class="kaicode-award-banner__title">
                    {{ copy.title }}
                </h2>
                <a
                    class="kaicode-award-banner__cta"
                    href="https://www.kaicode.org/2026.html"
                    target="_blank"
                    rel="noopener noreferrer"
                >
                    {{ copy.cta }} <span aria-hidden="true">↗</span>
                </a>
            </div>
        </div>
    </section>
</template>

<style scoped>
.kaicode-award-banner {
    padding: 0 24px 64px;
}

.kaicode-award-banner__inner {
    position: relative;
    display: grid;
    grid-template-columns: minmax(200px, 280px) minmax(0, 1fr);
    align-items: center;
    gap: clamp(32px, 6vw, 72px);
    box-sizing: border-box;
    max-width: 1152px;
    margin: 0 auto;
    padding: clamp(28px, 4vw, 44px);
    overflow: hidden;
    background:
        radial-gradient(circle at 12% 24%, rgba(255, 190, 95, 0.2), transparent 34%),
        radial-gradient(circle at 88% 78%, rgba(208, 1, 27, 0.08), transparent 38%),
        linear-gradient(
                135deg,
                rgba(211, 134, 45, 0.16),
                rgba(208, 1, 27, 0.06) 52%,
                var(--vp-c-bg-soft)
        );
    background-position: 0 0, 100% 100%, 0 0;
    background-size: 130% 130%, 140% 140%, 100% 100%;
    border: 1px solid rgba(211, 134, 45, 0.42);
    border-radius: 20px;
    box-shadow: 0 18px 48px rgba(67, 42, 14, 0.12);
}

.kaicode-award-banner__inner::before {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 2;
    width: 5px;
    background: linear-gradient(180deg, #d3862d, #d0011b);
    content: "";
}

.kaicode-award-banner__inner::after {
    position: absolute;
    top: -72%;
    left: -44%;
    z-index: 0;
    width: 32%;
    height: 244%;
    pointer-events: none;
    background: linear-gradient(
            90deg,
            transparent,
            rgba(255, 220, 166, 0.06) 28%,
            rgba(255, 225, 181, 0.36) 50%,
            rgba(211, 134, 45, 0.08) 72%,
            transparent
    );
    filter: blur(3px);
    content: "";
    transform: rotate(18deg);
}

.kaicode-award-banner__confetti {
    position: absolute;
    inset: 0;
    z-index: 3;
    overflow: hidden;
    pointer-events: none;
}

.kaicode-award-banner__confetti-piece {
    position: absolute;
    top: -24px;
    left: var(--confetti-x);
    width: var(--confetti-width);
    height: var(--confetti-height);
    opacity: 0;
    background: var(--confetti-color);
    border-radius: 2px;
    box-shadow: 0 0 8px color-mix(in srgb, var(--confetti-color) 45%, transparent);
}

.kaicode-award-banner__confetti-piece:nth-child(3n) {
    border-radius: 50%;
}

.kaicode-award-banner__confetti-piece:nth-child(4n) {
    clip-path: polygon(50% 0, 100% 50%, 50% 100%, 0 50%);
}

.kaicode-award-banner__artwork-link {
    position: relative;
    z-index: 1;
    justify-self: center;
    isolation: isolate;
}

.kaicode-award-banner__artwork-link::before {
    position: absolute;
    inset: -18px;
    z-index: -1;
    background: conic-gradient(
            from 0deg,
            transparent 0 18%,
            rgba(240, 173, 93, 0.72) 28%,
            rgba(208, 1, 27, 0.5) 42%,
            transparent 58% 82%,
            rgba(240, 173, 93, 0.55) 92%
    );
    border-radius: 26px;
    filter: blur(12px);
    opacity: 0.72;
    content: "";
}

.kaicode-award-banner__artwork-link::after {
    position: absolute;
    top: 2%;
    right: 2%;
    z-index: 2;
    width: 22px;
    height: 22px;
    background: linear-gradient(135deg, #fff8dc, #f0ad5d);
    clip-path: polygon(
            50% 0,
            61% 39%,
            100% 50%,
            61% 61%,
            50% 100%,
            39% 61%,
            0 50%,
            39% 39%
    );
    filter: drop-shadow(0 0 8px rgba(255, 214, 147, 0.8));
    content: "";
}

.kaicode-award-banner__artwork {
    display: block;
    box-sizing: border-box;
    width: 280px;
    max-width: 100%;
    height: auto;
    padding: 16px;
    background-color: #fff;
    border-radius: 16px;
    box-shadow: 0 12px 30px rgba(67, 42, 14, 0.22);
}

.kaicode-award-banner__content {
    position: relative;
    z-index: 1;
}

.kaicode-award-banner__eyebrow {
    margin: 0 0 8px;
    color: #a8600d;
    font-size: 13px;
    font-weight: 700;
    letter-spacing: 0.12em;
    text-transform: uppercase;
}

.kaicode-award-banner__title {
    margin: 0 0 22px;
    color: var(--vp-c-text-1);
    font-size: clamp(30px, 4vw, 44px);
    line-height: 1.12;
}

.kaicode-award-banner__cta {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 9px 14px;
    color: #914f06;
    font-size: 14px;
    font-weight: 700;
    text-decoration: none;
    background: rgba(211, 134, 45, 0.13);
    border: 1px solid rgba(211, 134, 45, 0.45);
    border-radius: 999px;
}

:global(html.dark) .kaicode-award-banner__inner {
    background:
        radial-gradient(circle at 12% 24%, rgba(240, 173, 93, 0.2), transparent 34%),
        radial-gradient(circle at 88% 78%, rgba(208, 1, 27, 0.11), transparent 38%),
        linear-gradient(
                135deg,
                rgba(211, 134, 45, 0.14),
                rgba(208, 1, 27, 0.07) 52%,
                var(--vp-c-bg-soft)
        );
    background-position: 0 0, 100% 100%, 0 0;
    background-size: 130% 130%, 140% 140%, 100% 100%;
    box-shadow: 0 18px 48px rgba(0, 0, 0, 0.28);
}

:global(html.dark) .kaicode-award-banner__inner::after {
    background: linear-gradient(
            90deg,
            transparent,
            rgba(255, 214, 147, 0.05) 28%,
            rgba(255, 225, 181, 0.28) 50%,
            rgba(240, 173, 93, 0.07) 72%,
            transparent
    );
}

:global(html.dark) .kaicode-award-banner__eyebrow {
    color: #f0ad5d;
}

:global(html.dark) .kaicode-award-banner__cta {
    color: #ffd29b;
    background: rgba(211, 134, 45, 0.18);
}

@media (prefers-reduced-motion: no-preference) {
    .kaicode-award-banner.is-celebrating .kaicode-award-banner__confetti-piece {
        animation: kaicode-award-confetti-fall var(--confetti-duration) cubic-bezier(0.18, 0.65, 0.35, 1) var(--confetti-delay) both;
        will-change: transform, opacity;
    }

    .kaicode-award-banner__inner {
        animation:
            kaicode-award-reveal 0.9s cubic-bezier(0.16, 1, 0.3, 1) both,
            kaicode-award-background 8s ease-in-out 1s infinite alternate;
        will-change: transform, opacity, background-position;
    }

    .kaicode-award-banner__inner::before {
        animation: kaicode-award-accent-pulse 2.8s ease-in-out 1s infinite;
    }

    .kaicode-award-banner__inner::after {
        animation: kaicode-award-sweep 5.4s cubic-bezier(0.45, 0, 0.2, 1) 0.9s infinite;
        will-change: transform, opacity;
    }

    .kaicode-award-banner__artwork-link {
        animation: kaicode-award-float 4.2s ease-in-out 0.8s infinite;
        transform-style: preserve-3d;
        will-change: transform;
    }

    .kaicode-award-banner__artwork-link::before {
        animation: kaicode-award-halo 7s linear infinite;
        will-change: transform;
    }

    .kaicode-award-banner__artwork-link::after {
        animation: kaicode-award-sparkle 2.4s ease-in-out 0.8s infinite;
    }

    .kaicode-award-banner__eyebrow,
    .kaicode-award-banner__title,
    .kaicode-award-banner__cta {
        animation: kaicode-award-content-reveal 0.7s cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    .kaicode-award-banner__eyebrow {
        animation-delay: 0.18s;
    }

    .kaicode-award-banner__title {
        color: transparent;
        background: linear-gradient(
                105deg,
                var(--vp-c-text-1) 0 38%,
                #f0ad5d 48%,
                #fff0d1 51%,
                var(--vp-c-text-1) 62% 100%
        );
        background-clip: text;
        background-position: 110% 0;
        background-size: 260% 100%;
        -webkit-background-clip: text;
        animation:
            kaicode-award-content-reveal 0.7s cubic-bezier(0.16, 1, 0.3, 1) 0.28s both,
            kaicode-award-title-shimmer 5.2s ease-in-out 1.4s infinite;
    }

    .kaicode-award-banner__cta {
        animation:
            kaicode-award-content-reveal 0.7s cubic-bezier(0.16, 1, 0.3, 1) 0.38s both,
            kaicode-award-cta-pulse 3.4s ease-in-out 1.5s infinite;
    }

    .kaicode-award-banner__artwork,
    .kaicode-award-banner__cta {
        transition: transform 0.2s ease, box-shadow 0.2s ease, background-color 0.2s ease;
    }

    .kaicode-award-banner__artwork-link:hover .kaicode-award-banner__artwork {
        transform: translateY(-3px);
        box-shadow: 0 16px 36px rgba(67, 42, 14, 0.28);
    }

    .kaicode-award-banner__cta:hover {
        background: rgba(211, 134, 45, 0.22);
        transform: translateY(-1px);
    }
}

@keyframes kaicode-award-confetti-fall {
    0% {
        opacity: 0;
        transform: translate3d(0, -32px, 0) rotateX(0) rotateZ(0) scale(0.6);
    }

    9% {
        opacity: 1;
    }

    38% {
        opacity: 1;
        transform:
            translate3d(calc(var(--confetti-drift) * -0.28), 180px, 0)
            rotateX(calc(var(--confetti-spin) * 0.32))
            rotateZ(calc(var(--confetti-spin) * 0.18))
            scale(1);
    }

    72% {
        opacity: 0.95;
        transform:
            translate3d(calc(var(--confetti-drift) * 0.64), 390px, 0)
            rotateX(calc(var(--confetti-spin) * 0.7))
            rotateZ(calc(var(--confetti-spin) * 0.66))
            scale(0.92);
    }

    100% {
        opacity: 0;
        transform:
            translate3d(var(--confetti-drift), 720px, 0)
            rotateX(var(--confetti-spin))
            rotateZ(calc(var(--confetti-spin) * 0.86))
            scale(0.72);
    }
}

@keyframes kaicode-award-reveal {
    0% {
        opacity: 0;
        filter: blur(10px);
        transform: translateY(32px) scale(0.96);
    }

    62% {
        opacity: 1;
    }

    100% {
        opacity: 1;
        filter: blur(0);
        transform: translateY(0) scale(1);
    }
}

@keyframes kaicode-award-content-reveal {
    from {
        opacity: 0;
        filter: blur(4px);
        transform: translateY(18px);
    }

    to {
        opacity: 1;
        filter: blur(0);
        transform: translateY(0);
    }
}

@keyframes kaicode-award-float {
    0%,
    100% {
        transform: perspective(900px) translateY(0) rotateX(0) rotateY(-1.5deg) scale(1);
    }

    50% {
        transform: perspective(900px) translateY(-11px) rotateX(1.5deg) rotateY(1.5deg) scale(1.02);
    }
}

@keyframes kaicode-award-background {
    from {
        background-position: 0 0, 100% 100%, 0 0;
    }

    to {
        background-position: 18% 12%, 82% 86%, 0 0;
    }
}

@keyframes kaicode-award-sweep {
    0%,
    12% {
        opacity: 0;
        transform: translate3d(-40%, 0, 0) rotate(18deg);
    }

    22% {
        opacity: 0.9;
    }

    58% {
        opacity: 0.65;
    }

    72%,
    100% {
        opacity: 0;
        transform: translate3d(520%, 0, 0) rotate(18deg);
    }
}

@keyframes kaicode-award-halo {
    to {
        transform: rotate(1turn);
    }
}

@keyframes kaicode-award-sparkle {
    0%,
    100% {
        opacity: 0.42;
        transform: scale(0.72) rotate(0);
    }

    50% {
        opacity: 1;
        transform: scale(1.2) rotate(90deg);
    }
}

@keyframes kaicode-award-title-shimmer {
    0%,
    22% {
        background-position: 110% 0;
    }

    56%,
    100% {
        background-position: -25% 0;
    }
}

@keyframes kaicode-award-cta-pulse {
    0%,
    100% {
        box-shadow: 0 0 0 0 rgba(211, 134, 45, 0);
    }

    50% {
        box-shadow:
            0 0 0 5px rgba(211, 134, 45, 0.08),
            0 0 24px rgba(211, 134, 45, 0.24);
    }
}

@keyframes kaicode-award-accent-pulse {
    0%,
    100% {
        box-shadow: 0 0 0 rgba(208, 1, 27, 0);
    }

    50% {
        box-shadow: 0 0 18px rgba(208, 1, 27, 0.62);
    }
}

@media (max-width: 767px) {
    .kaicode-award-banner {
        padding: 0 24px 48px;
    }

    .kaicode-award-banner__inner {
        grid-template-columns: minmax(0, 1fr);
        gap: 24px;
        padding: 28px 24px;
        text-align: center;
    }

    .kaicode-award-banner__artwork {
        width: 220px;
        padding: 14px;
    }
}

@media (max-width: 420px) {
    .kaicode-award-banner {
        padding-right: 16px;
        padding-left: 16px;
    }

    .kaicode-award-banner__inner {
        padding: 24px 20px;
    }

    .kaicode-award-banner__artwork {
        width: 200px;
    }
}
</style>
