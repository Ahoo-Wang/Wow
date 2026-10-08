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
  Overview: "总览",
  "Event stream": "事件流",
  Boards: "看板",
  "Compensation console": "补偿控制台",
  Appearance: "明暗",
  "Appearance: {mode}": "明暗：{mode}",
  "Follow system": "跟随系统",
  Light: "亮",
  Dark: "暗",
  "Open in the dashboard workbench": "在仪表盘工作台中打开",
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
  Executing: "执行中",
  "Next retry": "下次重试",
  Succeeded: "已成功",
  Unrecoverable: "不可恢复",
  Failed: "失败",
  Prepared: "已准备",
  Unknown: "未知",
  Recoverable: "可恢复",
  English: "English",
  "Current language: {language}": "当前语言：{language}",
  "Primary navigation": "主导航",
  "Wow Compensation Console": "Wow 补偿控制台",
  "Open navigation": "打开导航",
  "Skip to main content": "跳到主要内容",
  "GitHub commit {commit}": "GitHub 提交 {commit}",
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
  "Retry specification updated": "重试规格已更新",
  "Max retries": "最大重试次数",
  "Min backoff (s)": "最小退避时间（秒）",
  "Execution timeout (s)": "执行超时（秒）",
  "Enter a duration": "请输入时长",
  "Applying…": "正在应用…",
  "Apply retry spec": "应用重试规格",
  "Function updated": "函数已更新",
  "Enter a whole number": "请输入整数",
  Required: "必填",
  "Why it failed": "失败原因",
  "No error message": "没有错误信息",
  "Show the first lines only": "仅显示前几行",
  "Show all {count} lines": "展开全部 {count} 行",
  "Not retried: marked unrecoverable": "已标记为不可恢复，不再重试",
  "Retry limit reached": "已达重试上限",
  "Recoverability: {value}": "可恢复性：{value}",
  Retries: "已重试",
  "Next automatic retry": "下次自动重试",
  "First failed": "首次失败",
  "Timeout per attempt": "单次超时",
  "Backoff at least {duration}": "退避至少 {duration}",
  "The last {count} attempts failed with this same error":
    "最近 {count} 次尝试都因这个错误失败",
  "It looks like a fault in the code or its data rather than a passing one, so retrying as it is will likely fail again. Fix the handler and change the function, or mark it unrecoverable.":
    "这更像是代码或数据的问题，而不是暂时故障，照原样重试大概率还会失败。修好处理函数后变更函数，或把它标记为不可恢复。",
  Attempts: "尝试记录",
  "Attempt {number}": "第 {number} 次",
  "Function changed": "变更了函数",
  "Retry specification changed": "变更了重试规格",
  "Marked {value}": "标记为{value}",
  "Could not read the attempts.": "读不到尝试记录。",
  "Only the latest {count} events are read here.":
    "这里只读了最近 {count} 个事件。",
  "All events ({count})": "全部事件（{count}）",
  "All events": "全部事件",
  Service: "服务",
  Handler: "处理函数",
  "Triggering event": "触发它的事件",
  Aggregate: "聚合",
  "Aggregate ID": "聚合 ID",
  Tenant: "租户",
  "Up to {max} retries · backoff at least {backoff} · timeout {timeout}":
    "最多重试 {max} 次 · 退避至少 {backoff} · 单次超时 {timeout}",
  "Retry specification": "重试规格",
  "Last executed": "最近执行",
  "Last updated": "最近更新",
  "Context name": "上下文名称",
  "Function name": "函数名称",
  "Function kind": "函数类型",
  Event: "事件",
  "State event": "状态事件",
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
  "Copy {value}": "复制 {value}",
  "Unable to copy {value}": "无法复制 {value}",
  Copied: "已复制",
  "Stack trace content": "堆栈跟踪内容",
  "Something went wrong.": "出现错误。",
  "The dashboard could not render this view. Try again to recover from a temporary problem.":
    "仪表盘无法呈现此视图，请重试以恢复临时故障。",
  "Try again": "重试",
  "Technical details": "技术详情",
  "Time range": "时间范围",
  "Compensation overview": "补偿概览",
  "Actionable now": "当前可操作",
  "Invalid time range filter.": "时间范围过滤条件无效。",
  "Clear time range filter": "清除时间范围过滤",
  "Timed out": "已超时",
  "New failures": "新增失败",
  "Net backlog": "净积压",
  "Retry success": "重试成功率",
  Oldest: "最早",
  "Compensation activity": "补偿活动",
  Time: "时间",
  Prepare: "准备",
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
    document.title = translate(locale, "Wow Compensation Console");
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
