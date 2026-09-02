import {defineConfig} from 'vitepress'
import llmstxt from 'vitepress-plugin-llms'
import {copyOrDownloadAsMarkdownButtons} from 'vitepress-plugin-llms'
import {MermaidMarkdown} from "vitepress-plugin-mermaid";
import {navbarZh} from "./configs/navbar.zh";
import {sidebarZh} from "./configs/sidebar.zh";
import {navbarEn} from "./configs/navbar.en";
import {sidebarEn} from "./configs/sidebar.en";
import {head} from "./configs/head";

// https://vitepress.dev/reference/site-config
const userConfig = defineConfig({
    // Local Swagger links are runnable-example targets, not documentation pages.
    // Keep VitePress dead-link validation enabled for every other link.
    ignoreDeadLinks: 'localhostLinks',
    head: head,
    rewrites: (id) => id.startsWith('en/') ? id.slice(3) : id,
    transformHead({page, title, description}) {
        const isChinese = page.startsWith('zh/')
        const isArticle = (page.startsWith('articles/') || page.startsWith('zh/articles/')) &&
            !page.endsWith('articles/index.md')
        return [
            ['meta', {property: 'og:site_name', content: 'Wow'}],
            ['meta', {property: 'og:type', content: isArticle ? 'article' : 'website'}],
            ['meta', {property: 'og:title', content: title}],
            ['meta', {property: 'og:description', content: description}],
            ['meta', {property: 'og:locale', content: isChinese ? 'zh_CN' : 'en_US'}],
            ['meta', {name: 'twitter:card', content: 'summary'}],
            ['meta', {name: 'twitter:title', content: title}],
            ['meta', {name: 'twitter:description', content: description}],
        ]
    },
    sitemap: {
        hostname: 'https://wow.ahoo.me/',
        transformItems: (items) => {
            items.push({
                url: 'https://wow.ahoo.me/dokka/index.html',
                changefreq: 'weekly',
                priority: 0.8
            })
            return items
        }
    },
    appearance: 'dark',
    themeConfig: {
        logo: '/images/logo.svg',
        siteTitle: '领域模型即服务 | Wow',
        search: {provider: 'local',},
        editLink: {
            pattern: 'https://github.com/Ahoo-Wang/Wow/edit/main/documentation/docs/:path'
        },
        // https://vitepress.dev/reference/default-theme-config
        socialLinks: [
            {icon: 'github', link: 'https://github.com/Ahoo-Wang/Wow'},
            {icon: 'gitee', link: 'https://gitee.com/AhooWang/Wow'}
        ],
        aside: true,
        externalLinkIcon: true,
        footer: {
            message: 'Released under the Apache 2.0 License.',
            copyright: 'Copyright © 2022-present <a href="https://github.com/Ahoo-Wang" target="_blank">Ahoo Wang</a>'
        },
    },
    vite: {
        plugins: [llmstxt({
            ignoreFiles: ['index.md'],
            ignoreFilesPerOutput: {
                llmsTxt: ['zh.md', 'zh/**'],
                llmsFullTxt: ['zh.md', 'zh/**']
            }
        })],
        optimizeDeps: {
            include: ['mermaid']
        }
    },
    markdown: {
        config(md) {
            md.use(copyOrDownloadAsMarkdownButtons)
            md.use(MermaidMarkdown)
        }
    },
    locales: {
        root: {
            label: 'English',
            lang: 'en-US',
            title: 'Wow',
            description: 'Wow - Domain Model as a Service | Modern Reactive CQRS Architecture Microservice development framework based on DDD and EventSourcing.',
            themeConfig: {
                siteTitle: 'Wow',
                lastUpdated: {
                    text: 'Last updated'
                },
                outline: {
                    label: 'On this page',
                    level: [2, 3]
                },
                nav: navbarEn,
                sidebar: sidebarEn,
                notFound: {
                    title: 'Page Not Found',
                    quote: 'The page you are looking for does not exist.',
                    linkText: 'Go home'
                }
            }
        },
        zh: {
            label: '中文',
            lang: 'zh-CN',
            link: '/zh/',
            title: 'Wow',
            description: 'Wow - 领域模型即服务 | 基于 DDD & EventSourcing 的现代响应式 CQRS 架构微服务开发框架 | Modern Reactive CQRS Architecture Microservice development framework based on DDD and EventSourcing.',
            themeConfig: {
                siteTitle: 'Wow',
                lastUpdated: {
                    text: '上次更新'
                },
                outline: {
                    label: '本页目录',
                    level: [2, 3]
                },
                editLink: {
                    text: '在 GitHub 上编辑此页面'
                },
                docFooter: {
                    prev: '上一页',
                    next: '下一页'
                },
                langMenuLabel: '切换语言',
                darkModeSwitchLabel: '外观',
                lightModeSwitchTitle: '切换到浅色主题',
                darkModeSwitchTitle: '切换到深色主题',
                sidebarMenuLabel: '菜单',
                returnToTopLabel: '返回顶部',
                skipToContentLabel: '跳到正文',
                nav: navbarZh,
                sidebar: sidebarZh,
                notFound: {
                    title: '页面未找到',
                    quote: '你访问的页面不存在。',
                    linkText: '返回首页'
                }
            }
        }
    }
})

export default userConfig
