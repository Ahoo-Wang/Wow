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

/* eslint-disable react-refresh/only-export-components -- The tiny locale API and provider intentionally live together. */

import {
  createContext,
  useContext,
  useCallback,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from "react";

export type Locale = "en" | "zh-CN";

const zhCN = {
  "Execution is in progress; wait until it times out.":
    "执行尚未超时，请等待当前执行结果。",
  "Due for retry": "已到重试时间",
  Overview: "概览",
  "The link's condition cannot be read, so no records are shown.":
    "链接里的条件读不出来，所以不显示任何记录。",
  "This view is narrowed by the link it was opened from.":
    "此视图按打开它的链接限定了范围。",
  "Remove the narrowing": "移除限定",
  "Execution history": "执行历史",
  "No stack trace": "没有堆栈",
  "{count} line": "{count} 行",
  "{count} lines": "{count} 行",
  "Wrap lines": "自动换行",
  "Clear cluster filter": "清除集群筛选",
  "Invalid cluster filter.": "集群筛选无效。",
  "View cluster {code}": "查看失败集群 {code}",
  Dashboard: "仪表盘",
  Executing: "执行中",
  "Next Retry": "下次重试",
  "Next retry": "下次重试",
  Succeeded: "已成功",
  Unrecoverable: "不可恢复",
  Failed: "失败",
  Prepared: "已准备",
  Unknown: "未知",
  Recoverable: "可恢复",
  Language: "语言",
  English: "English",
  Chinese: "中文",
  "Current language: {language}": "当前语言：{language}",
  "Primary navigation": "主导航",
  "Application sidebar": "应用侧栏",
  Navigation: "导航",
  "Compensation Control Plane": "补偿控制台",
  "Wow compensation dashboard": "Wow 补偿仪表盘",
  "Wow Compensation Dashboard": "Wow 补偿仪表盘",
  Compensation: "补偿",
  "Control Plane": "控制台",
  "Close navigation": "关闭导航",
  "Open navigation": "打开导航",
  "Collapse navigation": "收起导航",
  "Expand navigation": "展开导航",
  Collapse: "收起",
  Expand: "展开",
  Close: "关闭",
  Sidebar: "侧栏",
  "Displays the mobile sidebar.": "显示移动端侧栏。",
  "Toggle Sidebar": "切换侧栏",
  "Skip to main content": "跳到主要内容",
  "Build information": "构建信息",
  "GitHub commit {commit}": "GitHub 提交 {commit}",
  "Project repositories": "项目仓库",
  "Page {page}": "第 {page} 页",
  "Execution ID": "执行 ID",
  "Event ID": "事件 ID",
  "Processor context": "处理器上下文",
  "Processor name": "处理器名称",
  Search: "搜索",
  Status: "状态",
  Retry: "重试",
  "Failed executions": "失败执行",
  "This execution has already succeeded.": "此执行记录已成功。",
  "Retry limit reached; force prepare remains available.":
    "已达到重试上限；仍可使用强制准备。",
  "Force prepare": "强制准备",
  Cancel: "取消",
  "Retry specification updated": "重试规格已更新",
  "Max retries": "最大重试次数",
  "Min backoff (s)": "最小退避时间（秒）",
  "Execution timeout (s)": "执行超时（秒）",
  "Enter a duration": "请输入时长",
  "Applying…": "正在应用…",
  "Apply retry spec": "应用重试规格",
  "Function updated": "函数已更新",
  "Context name": "上下文名称",
  "Function name": "函数名称",
  "Function kind": "函数类型",
  "Saving…": "正在保存…",
  "Save function": "保存函数",
  "Apply retry specification": "应用重试规格",
  Retryable: "可重试",
  "Change function": "变更函数",
  Processor: "处理器",
  "Event version": "事件版本",
  "Stack trace": "堆栈跟踪",
  "Unable to copy stack trace": "无法复制堆栈跟踪",
  "Stack trace copied": "堆栈跟踪已复制",
  "Copy stack trace": "复制堆栈跟踪",
  "Stack trace content": "堆栈跟踪内容",
  value: "内容",
  "Version {version}": "版本 {version}",
  "Something went wrong.": "出现错误。",
  "The dashboard could not render this view. Try again to recover from a temporary problem.":
    "仪表盘无法呈现此视图，请重试以恢复临时故障。",
  "Try again": "重试",
  "Technical details": "技术详情",
  "Loading dashboard": "正在加载仪表盘",
  "Loading compensation overview": "正在加载补偿概览",
  "Loading dashboard activity": "正在加载仪表盘活动",
  "Time range": "时间范围",
  "Time range: {range}": "时间范围：{range}",
  "Date range presets": "日期范围预设",
  Today: "今天",
  "Last 7 days": "最近 7 天",
  "Last 30 days": "最近 30 天",
  "Select an end date.": "请选择结束日期。",
  Apply: "应用",
  "Updated {date}": "更新于 {date}",
  "Refreshing dashboard": "正在刷新仪表盘",
  "Refresh dashboard": "刷新仪表盘",
  Refresh: "刷新",
  "Compensation overview": "补偿概览",
  "Backlog exposure": "积压暴露",
  "STOCK / Backlog exposure": "存量 / 积压暴露",
  "Selected active": "所选活跃记录",
  "Older backlog": "更早的积压",
  Coverage: "覆盖率",
  "Selected active coverage": "所选活跃记录覆盖率",
  "{count} selected ({percentage})": "所选 {count} 条（{percentage}）",
  "{count} older": "更早 {count} 条",
  "{count} newer": "更新 {count} 条",
  "{count} total": "共 {count} 条",
  "Actionable now": "当前可操作",
  "{label}: {count}, open the due-for-retry queue": "{label}：{count} 条，打开已到重试时间的队列",
  "Invalid time range filter.": "时间范围过滤条件无效。",
  "Clear time range filter": "清除时间范围过滤",
  "Timed out": "已超时",
  "Backlog exposure unavailable.": "积压暴露数据不可用。",
  "Compensation effectiveness": "补偿效果",
  "FLOW / Compensation effectiveness": "流量 / 补偿效果",
  "New failures": "新增失败",
  "Net backlog": "净积压",
  "Retry success": "重试成功率",
  "Retried failed": "重试失败",
  "Compensation effectiveness unavailable.": "补偿效果数据不可用。",
  "Dashboard activity": "仪表盘活动",
  "Compensation activity unavailable.": "补偿活动数据不可用。",
  "Current health for selected execution range": "所选执行时间范围的当前健康状况",
  "Recoverability composition": "可恢复性构成",
  "Failure concentration · Top cluster {percentage}": "失败集中度 · 首位集群 {percentage}",
  "Current failure pressure — Top 5 clusters": "当前失败压力 — 前 5 个集群",
  "{count} cluster": "{count} 个集群",
  "{count} clusters": "{count} 个集群",
  "Top 5 clusters": "前 5 个集群",
  "Top cluster {percentage}": "首位集群 {percentage}",
  "Current failure pressure": "当前失败压力",
  Cluster: "集群",
  Current: "当前",
  "Failed / Prepared": "失败 / 已准备",
  Oldest: "最早",
  "No active failure clusters": "没有活跃的失败集群",
  "Failed {failed}; Prepared {prepared}": "失败 {failed}；已准备 {prepared}",
  "Retry distribution": "重试分布",
  "Retry distribution: {rows}": "重试分布：{rows}",
  "Total {total}": "总计 {total}",
  "Compensation activity": "补偿活动",
  "Daily trend": "每日趋势",
  "{count} new failures": "新增失败 {count} 条",
  "Outcome flow (total in selected range)": "结果流（所选范围内总计）",
  "Outcome flow: {rows}": "结果流：{rows}",
  "Compensation outcomes data": "补偿结果数据",
  Time: "时间",
  "{key} retries": "重试 {key} 次",
  Prepare: "准备",
  "Prepare {count}": "准备 {count} 条",
  "Actions for {id}": "{id} 的操作",
  "Mark as": "标记为",
  "Mark as {value}": "标记为{value}",
  "Mark recoverability": "标记可恢复性",
  "Prepare {count} execution?": "准备 {count} 条执行记录？",
  "Prepare {count} executions?": "准备 {count} 条执行记录？",
  "Force prepare {count} execution?": "强制准备 {count} 条执行记录？",
  "Force prepare {count} executions?": "强制准备 {count} 条执行记录？",
  "Mark {count} execution as {value}?": "将 {count} 条执行记录标记为{value}？",
  "Mark {count} executions as {value}?": "将 {count} 条执行记录标记为{value}？",
  "Each one is prepared within its retry spec.": "每条都在其重试规格内准备。",
  "This bypasses the retry limit. The server still validates each execution's state.":
    "这会绕过重试上限，服务端仍会校验每条执行记录的状态。",
  "The scheduler stops retrying unrecoverable executions.":
    "调度器不再重试不可恢复的执行记录。",
  "This changes whether the scheduler retries them.":
    "这会改变调度器是否重试它们。",
  "The ones the server refuses stay selected, with its reason.":
    "服务端拒绝的记录仍保持勾选，并写明原因。",
  "Not sent, and left selected:": "以下不会发送，仍保持勾选：",
  "{reason} ({count})": "{reason}（{count} 条）",
} as const;

export type Message = keyof typeof zhCN;
export type TranslationValues = Record<string, string | number>;
export type Translate = (
  message: Message,
  values?: TranslationValues,
) => string;

interface I18nContextValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: Translate;
}

const localeStorageKey = "wow-dashboard-locale";

function loadSavedLocale(): string | null {
  try {
    return localStorage.getItem(localeStorageKey);
  } catch {
    return null;
  }
}

function saveLocale(locale: Locale) {
  try {
    localStorage.setItem(localeStorageKey, locale);
  } catch {
    // A blocked UI preference must not prevent the dashboard from working.
  }
}

export function translate(
  locale: Locale,
  message: Message,
  values: TranslationValues = {},
): string {
  const template = locale === "zh-CN" ? zhCN[message] : message;
  return template.replace(/\{(\w+)}/g, (placeholder, name: string) =>
    Object.hasOwn(values, name) ? String(values[name]) : placeholder,
  );
}

const I18nContext = createContext<I18nContextValue>({
  locale: "en",
  setLocale: () => undefined,
  t: (message, values) => translate("en", message, values),
});

export function resolveLocale(
  savedLocale: string | null,
  languages: readonly string[],
): Locale {
  if (savedLocale === "en" || savedLocale === "zh-CN") {
    return savedLocale;
  }
  for (const language of languages) {
    const normalized = language.toLowerCase();
    if (normalized.startsWith("zh")) {
      return "zh-CN";
    }
    if (normalized.startsWith("en")) {
      return "en";
    }
  }
  return "en";
}

export function I18nProvider({ children }: PropsWithChildren) {
  const [locale, setCurrentLocale] = useState<Locale>(() =>
    resolveLocale(
      loadSavedLocale(),
      navigator.languages ?? [navigator.language],
    ),
  );
  const setLocale = useCallback((nextLocale: Locale) => {
    setCurrentLocale(nextLocale);
    saveLocale(nextLocale);
  }, []);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = translate(locale, "Wow Compensation Dashboard");
  }, [locale]);

  const value = useMemo<I18nContextValue>(
    () => ({
      locale,
      setLocale,
      t: (message, values) => translate(locale, message, values),
    }),
    [locale, setLocale],
  );

  return <I18nContext value={value}>{children}</I18nContext>;
}

export function useI18n() {
  return useContext(I18nContext);
}
