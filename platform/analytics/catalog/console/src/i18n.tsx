/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* eslint-disable react-refresh/only-export-components -- The provider and its hooks live together. */

import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from "react";
import { LOCALES, type Locale } from "@/views/words.ts";

export type { Locale } from "@/views/words.ts";

/** The shell's own words; the definitions' are under `views/`. */
const SHELL = {
  en: {
    documentTitle: "Wow Analytics Catalog",
    product: "Wow Analytics Catalog",
    language: "Language",
    caller: "Caller",
    signedOut: "No caller",
    callerTitle: "Local run: who is calling",
    callerDescription:
      "Only a development run of the catalog (a dev profile) reads the caller from request headers, standing in for CoSec: Wow-User names the data owner, Wow-Tenant its tenant, Wow-Roles its roles. An analyst needs analytics.catalog.editor, a reviewer analytics.catalog.approver.",
    signInTitle: "Sign in with CoSec",
    signInDescription:
      "Paste an access token your CoSec issued. The CoSec gateway in front of the catalog verifies it and forwards the data owner it names: its tenant, its id and its roles. Reading the catalog needs one of the analytics.catalog roles.",
    signedOutExpired: "Your CoSec access token has expired: sign in again.",
    signedOutRefused:
      "Your CoSec access token was refused (401): sign in with another.",
    signedOutHeaders:
      "This catalog is not a development run: it reads no caller from request headers. Sign in with a CoSec access token.",
    token: "Access token",
    signIn: "Sign in",
    tokenRefused: "This is not a CoSec access token, or it has expired.",
    signedInAs: "Signed in as {user} with a CoSec access token.",
    signOutToken: "Sign out",
    user: "User",
    tenant: "Tenant",
    roles: "Roles",
    rolesHint: "Comma-separated, e.g. analytics.catalog.editor",
    save: "Use this caller",
    cancel: "Cancel",
    signOut: "Clear",
    needCaller:
      "Sign in to read the catalog, or name a caller for a local run: the catalog answers an anonymous request with 401.",
    readOnly:
      "This caller only reads the catalog: drafting and publishing take analytics.catalog.editor (or admin), reviews and approvals analytics.catalog.approver.",
    noRole:
      "This caller holds none of the catalog's roles (analytics.catalog.viewer, editor, approver or admin): the catalog shows it nothing.",
    readingSettings: "Reading the catalog's settings…",
    none: "—",
    yes: "Yes",
    no: "No",
    send: "Save",
    sending: "Saving…",
    saved: "Saved.",
    columns: "Columns",
    column: "Column",
    type: "Type",
    scope: "Scope",
    scopeHint:
      "The tenant column keeps each tenant's rows apart; a platform dimension, every tenant's, declares none. Revising a reviewed source takes it back to its draft.",
    references: "References",
    referencesHint:
      "Columns that hold an aggregate's id: a dataset's rows act on that aggregate through its owning service. One per line, as column=context.aggregate.",
    referencesInvalid: "Line {line} does not read column=context.aggregate.",
    sensitivity: "Sensitivity",
    sensitivityHint:
      "Each string column's protection: Display masks it but for the characters kept, Confidential hides it. A save reviews every column not reviewed yet, None a protection too. A Wow table's protection may only be tightened here.",
    level: "Level",
    levelNone: "None",
    levelDisplay: "Display",
    levelConfidential: "Confidential",
    keepPrefix: "Keep first",
    keepSuffix: "Keep last",
    pending: "Lower protection awaiting an approver",
    notReviewed: "Not reviewed",
    refusals: "Refused catalog rows",
    enrichment: "From its wow_catalog row",
    readOnlySource: "A Wow table: its wow_catalog row declares its scope.",
    newSource: "Draft a source",
    newSourceHint:
      "An analyst's table or view of the warehouse: the catalog reads its columns, then it is reviewed before any dataset reads it.",
    database: "Database",
    table: "Table",
    draft: "Draft",
    newDataset: "Draft a dataset",
    newDatasetHint:
      "A wide table over the reviewed sources, served by its owning context's name: its definition names the sources, their joins, its columns and its filters.",
    context: "Context",
    name: "Name",
    title: "Title",
    definition: "Definition",
    definitionHint:
      "JSON: subject, sources (alias, sourceId), joins, columns (name, from or expression, sensitivity), filters, identity, eventTime.",
    definitionInvalid: "The definition is not JSON.",
    definitionInvalidAt:
      "The definition is not JSON: look at line {line}, column {column}.",
    definitionNotObject: "A definition is a JSON object.",
    requested: "Awaiting approval",
    requestReasons: "Why it takes an approver",
    problems: "Why it needs revision",
    versions: "Versions",
    version: "v{version}",
    serving: "Serving",
    rollBackTo: "Roll back to v{version}",
    rollBackAsk:
      "Queries will read v{version} again; its view is created again if it is gone.",
    rollBack: "Roll back",
    rolledBack: "Switched back to v{version}.",
    view: "View",
    sources: "Sources",
    fields: "Fields",
    ddl: "DDL",
    rows: "Rows",
    notServedHere:
      "This node serves no datasets: it has no dataset query backend (a local run on the memory backend has none). A dataset's rows are read on a node that serves them.",
    boards: "Dashboards",
    connection: "Connection",
    tableEngine: "Table engine",
    status: "Status",
    approved: "Approved",
    missing: "Table gone",
    platform: "Platform dimension",
    tenantColumn: "Tenant column",
    ownerColumn: "Owner column",
    spaceColumn: "Space column",
    aggregate: "Aggregate",
    writer: "Written by",
    time: "Written at",
    stillServing: "Serving while it needs revision",
    eventTime: "Event time",
    draftsHint:
      "Drafted sources and datasets open in their pages, where they are reviewed and published.",
    openSources: "Sources",
    openDatasets: "Datasets",
    connectionHint:
      "The catalog's warehouse connection (wow.analytics.catalog.connection): the first part of the source's id.",
    drafted: "Drafted {id}.",
    openDrafted: "Open it",
  },
  "zh-CN": {
    documentTitle: "Wow 数据目录",
    product: "Wow 数据目录",
    language: "语言",
    caller: "调用者",
    signedOut: "未指定调用者",
    callerTitle: "本地运行：调用者",
    callerDescription:
      "只有以开发模式运行的数据目录（dev profile）才从请求头读取调用者（代替 CoSec）：Wow-User 为数据负责人，Wow-Tenant 为其租户，Wow-Roles 为其角色。分析师需要 analytics.catalog.editor，审查者需要 analytics.catalog.approver。",
    signInTitle: "使用 CoSec 登录",
    signInDescription:
      "粘贴 CoSec 签发的访问令牌。数据目录前的 CoSec 网关验证令牌，并转发它所指的数据负责人：租户、标识与角色。查看目录需要任一 analytics.catalog 角色。",
    signedOutExpired: "CoSec 访问令牌已过期，请重新登录。",
    signedOutRefused: "CoSec 访问令牌被拒绝（401），请换一个登录。",
    signedOutHeaders:
      "这个数据目录不是开发模式运行，不从请求头读取调用者。请使用 CoSec 访问令牌登录。",
    token: "访问令牌",
    signIn: "登录",
    tokenRefused: "这不是 CoSec 访问令牌，或已过期。",
    signedInAs: "已使用 CoSec 访问令牌登录：{user}。",
    signOutToken: "退出登录",
    user: "用户",
    tenant: "租户",
    roles: "角色",
    rolesHint: "逗号分隔，例如 analytics.catalog.editor",
    save: "使用此调用者",
    cancel: "取消",
    signOut: "清除",
    needCaller:
      "请先登录，或为本地运行指定调用者：数据目录对匿名请求返回 401。",
    readOnly:
      "此调用者只能查看目录：起草与发布需要 analytics.catalog.editor（或 admin）角色，审查与审批需要 analytics.catalog.approver。",
    noRole:
      "此调用者没有任何数据目录角色（analytics.catalog.viewer、editor、approver 或 admin）：目录不向它显示任何内容。",
    readingSettings: "正在读取目录的设置…",
    none: "—",
    yes: "是",
    no: "否",
    send: "保存",
    sending: "正在保存…",
    saved: "已保存。",
    columns: "列",
    column: "列",
    type: "类型",
    scope: "作用域",
    scopeHint:
      "租户列使各租户的行互不相见；平台维度为所有租户共享，不声明租户列。修改已审查的数据源会使其回到草稿。",
    references: "引用",
    referencesHint:
      "存放聚合 ID 的列：数据集的行经其所属服务对该聚合执行操作。每行一个，格式为 列=上下文.聚合。",
    referencesInvalid: "第 {line} 行不是 列=上下文.聚合 的格式。",
    sensitivity: "敏感度",
    sensitivityHint:
      "每个字符串列的保护：“脱敏显示”除保留的字符外遮盖，“机密”完全隐藏。保存会审查所有尚未审查的列，“无”也是一种保护。Wow 表的保护在此只能提高。",
    level: "级别",
    levelNone: "无",
    levelDisplay: "脱敏显示",
    levelConfidential: "机密",
    keepPrefix: "保留前",
    keepSuffix: "保留后",
    pending: "待审批的降低保护",
    notReviewed: "未审查",
    refusals: "被拒绝的目录行",
    enrichment: "来自其 wow_catalog 行",
    readOnlySource: "Wow 表：由其 wow_catalog 行声明作用域。",
    newSource: "起草数据源",
    newSourceHint:
      "仓库中分析师的表或视图：目录读取其列，经审查后数据集才能读取它。",
    database: "库",
    table: "表",
    draft: "起草",
    newDataset: "起草数据集",
    newDatasetHint:
      "基于已审查数据源的宽表，以其所属上下文的名义提供：定义列出数据源、连接、列与过滤条件。",
    context: "上下文",
    name: "名称",
    title: "标题",
    definition: "定义",
    definitionHint:
      "JSON：subject、sources（alias、sourceId）、joins、columns（name、from 或 expression、sensitivity）、filters、identity、eventTime。",
    definitionInvalid: "定义不是合法的 JSON。",
    definitionInvalidAt:
      "定义不是合法的 JSON：请检查第 {line} 行第 {column} 列。",
    definitionNotObject: "定义须是一个 JSON 对象。",
    requested: "待审批",
    requestReasons: "需要审批的原因",
    problems: "需要修订的原因",
    versions: "版本",
    version: "v{version}",
    serving: "服务中",
    rollBackTo: "回滚到 v{version}",
    rollBackAsk: "查询将再次读取 v{version}；其视图如已不在会重新创建。",
    rollBack: "回滚",
    rolledBack: "已切回 v{version}。",
    view: "视图",
    sources: "数据源",
    fields: "字段",
    ddl: "DDL",
    rows: "数据行",
    notServedHere:
      "此节点不提供数据集：它没有数据集查询后端（以内存后端本地运行时没有）。数据集的行要在提供它们的节点上读取。",
    boards: "仪表盘",
    connection: "连接",
    tableEngine: "表引擎",
    status: "状态",
    approved: "已审批",
    missing: "表已不在",
    platform: "平台维度",
    tenantColumn: "租户列",
    ownerColumn: "所有者列",
    spaceColumn: "空间列",
    aggregate: "聚合",
    writer: "写入账户",
    time: "写入时间",
    stillServing: "需要修订期间仍在提供",
    eventTime: "事件时间",
    draftsHint: "起草的数据源与数据集在各自页面中审查与发布。",
    openSources: "数据源",
    openDatasets: "数据集",
    connectionHint:
      "目录的仓库连接（wow.analytics.catalog.connection）：数据源 ID 的第一部分。",
    drafted: "已起草 {id}。",
    openDrafted: "打开",
  },
} satisfies Record<Locale, Record<string, string>>;

export type Message = keyof (typeof SHELL)["en"];

export type Translate = (
  key: Message,
  values?: Record<string, string | number>,
) => string;

/** `key` in `locale`, with `{name}` replaced by `values`. */
export function translate(
  locale: Locale,
  key: Message,
  values: Record<string, string | number> = {},
): string {
  return SHELL[locale][key].replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in values ? String(values[name]) : whole,
  );
}

const STORAGE_KEY = "wow-analytics-catalog-console.locale";

/** The language kept on this machine, else the browser's. */
export function resolveLocale(
  stored: string | null,
  languages: readonly string[],
): Locale {
  if (stored && (LOCALES as readonly string[]).includes(stored))
    return stored as Locale;
  // The first of the browser's languages the console speaks, in its order.
  for (const language of languages) {
    const tag = language.toLowerCase();
    if (tag.startsWith("zh")) return "zh-CN";
    if (tag.startsWith("en")) return "en";
  }
  return "en";
}

function storedLocale(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

interface I18n {
  locale: Locale;
  setLocale(locale: Locale): void;
  t: Translate;
}

const I18nContext = createContext<I18n>({
  locale: "en",
  setLocale: () => {},
  t: (key, values) => translate("en", key, values),
});

export function I18nProvider({
  children,
  initial,
}: PropsWithChildren<{ initial?: Locale }>) {
  const [locale, setState] = useState<Locale>(
    () => initial ?? resolveLocale(storedLocale(), navigator.languages ?? []),
  );
  // The page says its language and its name in it.
  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = translate(locale, "documentTitle");
  }, [locale]);
  const value = useMemo<I18n>(
    () => ({
      locale,
      setLocale: (next) => {
        setState(next);
        try {
          localStorage.setItem(STORAGE_KEY, next);
        } catch {
          // Kept for this visit only.
        }
      },
      t: (key, values) => translate(locale, key, values),
    }),
    [locale],
  );
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18n {
  return useContext(I18nContext);
}
