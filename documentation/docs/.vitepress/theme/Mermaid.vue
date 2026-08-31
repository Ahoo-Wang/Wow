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
import {onMounted, ref, watch} from 'vue'
import {useData} from 'vitepress'

const props = withDefaults(defineProps<{
    graph: string
    id: string
    class?: string
}>(), {
    class: 'mermaid',
})
const {isDark} = useData()
const svg = ref('')

const renderChart = async () => {
    const {default: mermaid} = await import('mermaid')
    mermaid.initialize({
        securityLevel: 'loose',
        startOnLoad: false,
        theme: isDark.value ? 'dark' : 'default',
    })
    svg.value = (await mermaid.render(props.id, decodeURIComponent(props.graph))).svg
}

onMounted(() => void renderChart())
watch(isDark, () => void renderChart())
</script>

<template>
    <div v-html="svg" :class="props.class"></div>
</template>
