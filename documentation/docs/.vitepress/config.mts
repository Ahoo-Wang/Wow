import {defineConfig} from 'vitepress'
import llmstxt from 'vitepress-plugin-llms'
import { copyOrDownloadAsMarkdownButtons } from 'vitepress-plugin-llms'
import {navbarZh} from "./configs/navbar.zh";
import {sidebarZh} from "./configs/sidebar.zh";
import {navbarEn} from "./configs/navbar.en";
import {sidebarEn} from "./configs/sidebar.en";
import {head} from "./configs/head";
import {SITE_BASE} from "./configs/SITE_BASE";

let hostname = 'https://wow.ahoo.me/';
if (SITE_BASE == '/wow/') {
    hostname = 'https://ahoowang.gitee.io/wow/'
}

// https://vitepress.dev/reference/site-config
export default defineConfig({
    ignoreDeadLinks: true,
    head: head,
    base: SITE_BASE,
    rewrites: {
        'en/:rest*': ':rest*'
    },
    sitemap: {
        hostname: hostname,
        transformItems: (items) => {
            items.push({
                url: `${hostname}dokka/index.html`,
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
        // https://vitepress.dev/reference/default-theme-config
        socialLinks: [
            {icon: 'github', link: 'https://github.com/Ahoo-Wang/Wow'},
            {icon: 'gitee', link: 'https://gitee.com/AhooWang/Wow'}
        ],
        externalLinkIcon: true,
        footer: {
            message: 'Released under the Apache 2.0 License.',
            copyright: 'Copyright © 2022-present <a href="https://github.com/Ahoo-Wang" target="_blank">Ahoo Wang</a>'
        },
    },
    vite: {
        plugins: [llmstxt({workDir: 'en', ignoreFiles: ['index.md']})]
    },
    markdown: {
        config(md) {
            md.use(copyOrDownloadAsMarkdownButtons)
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
                editLink: {
                    pattern: 'https://github.com/Ahoo-Wang/Wow/edit/main/documentation/docs/:path'
                },
                lastUpdated: {
                    text: 'Last updated'
                },
                outline: {
                    label: 'On this page',
                    level: [2, 3]
                },
                aside: true,
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
                editLink: {
                    pattern: 'https://github.com/Ahoo-Wang/Wow/edit/main/documentation/docs/:path'
                },
                lastUpdated: {
                    text: '上次更新'
                },
                outline: {
                    label: '本页目录',
                    level: [2, 3]
                },
                aside: true,
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
