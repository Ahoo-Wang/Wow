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
import {computed} from 'vue'
import {useData, withBase} from 'vitepress'

const {frontmatter, lang} = useData()
const showAward = computed(() => frontmatter.value.kaicodeAward === true)
const isChinese = computed(() => lang.value.startsWith('zh'))
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
        class="kaicode-award-banner"
        aria-labelledby="kaicode-award-title"
    >
        <div class="kaicode-award-banner__inner">
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
    background: linear-gradient(
            135deg,
            rgba(211, 134, 45, 0.16),
            rgba(208, 1, 27, 0.06) 52%,
            var(--vp-c-bg-soft)
    );
    border: 1px solid rgba(211, 134, 45, 0.42);
    border-radius: 20px;
    box-shadow: 0 18px 48px rgba(67, 42, 14, 0.12);
}

.kaicode-award-banner__inner::before {
    position: absolute;
    inset: 0 auto 0 0;
    width: 5px;
    background: linear-gradient(180deg, #d3862d, #d0011b);
    content: "";
}

.kaicode-award-banner__artwork-link {
    position: relative;
    z-index: 1;
    justify-self: center;
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
    background: linear-gradient(
            135deg,
            rgba(211, 134, 45, 0.14),
            rgba(208, 1, 27, 0.07) 52%,
            var(--vp-c-bg-soft)
    );
    box-shadow: 0 18px 48px rgba(0, 0, 0, 0.28);
}

:global(html.dark) .kaicode-award-banner__eyebrow {
    color: #f0ad5d;
}

:global(html.dark) .kaicode-award-banner__cta {
    color: #ffd29b;
    background: rgba(211, 134, 45, 0.18);
}

@media (prefers-reduced-motion: no-preference) {
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
