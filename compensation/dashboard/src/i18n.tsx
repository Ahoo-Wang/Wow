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
  Dashboard: "仪表盘",
  "To Retry": "待重试",
  Executing: "执行中",
  "Next Retry": "下次重试",
  "Next retry": "下次重试",
  "Non Retryable": "不可重试",
  Succeeded: "已成功",
  Unrecoverable: "不可恢复",
  Failed: "失败",
  Prepared: "已准备",
  Unknown: "未知",
  Recoverable: "可恢复",
  Yes: "是",
  No: "否",
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
  "Previous page": "上一页",
  "Next page": "下一页",
  "Pagination options": "分页选项",
  "Go to page": "跳转到页",
  Go: "跳转",
  "Rows per page": "每页行数",
  "{first}–{last} of {total}": "第 {first}–{last} 条，共 {total} 条",
  "Search executions": "搜索执行记录",
  "Execution ID": "执行 ID",
  "Event ID": "事件 ID",
  "Aggregate ID": "聚合根 ID",
  "Aggregate context": "聚合上下文",
  "Aggregate name": "聚合名称",
  "Processor context": "处理器上下文",
  "Processor name": "处理器名称",
  "Search by ID…": "按 ID 搜索…",
  "Filter by event ID…": "按事件 ID 筛选…",
  "Filter by aggregate ID…": "按聚合根 ID 筛选…",
  "Filter by aggregate context…": "按聚合上下文筛选…",
  "Filter by aggregate name…": "按聚合名称筛选…",
  "Filter by processor context…": "按处理器上下文筛选…",
  "Filter by processor name…": "按处理器名称筛选…",
  Search: "搜索",
  "Add filter": "添加筛选条件",
  "Filters ({count})": "筛选条件（{count}）",
  "Search fields": "搜索字段",
  "Remove {label}": "移除{label}",
  "Exact match across all fields": "所有字段均使用精确匹配",
  "Clear all filters": "清除全部筛选条件",
  "Clear all": "全部清除",
  Status: "状态",
  "Processor / Function": "处理器 / 函数",
  Retry: "重试",
  Age: "已等待",
  "View execution {id}": "查看执行记录 {id}",
  "Loading page": "正在加载页面",
  "Refresh failed: {message}. Showing the last loaded page; changes are disabled until refresh succeeds.":
    "刷新失败：{message}。当前显示上次加载的页面；刷新成功前无法进行变更。",
  "Failed to load executions": "加载执行记录失败",
  "No failed executions found": "未找到失败执行记录",
  "Try another queue or adjust the filters.": "请切换队列或调整筛选条件。",
  "Clear search filters": "清除搜索筛选条件",
  "Clear search": "清除搜索",
  "Select an execution": "请选择一条执行记录",
  "Failure context and compensation actions will appear here.":
    "失败上下文和补偿操作将显示在这里。",
  "Loading page details": "正在加载页面详情",
  "The next executions will appear here shortly.": "稍后将在这里显示执行记录。",
  "Failed executions": "失败执行记录",
  "Execution failed details": "执行失败详情",
  "Inspect context and prepare compensation.": "检查上下文并准备补偿。",
  "Execution details panel": "执行详情面板",
  "Resize execution list and details": "调整执行列表和详情的大小",
  "Loading execution details": "正在加载执行详情",
  "Failed to load execution": "加载执行记录失败",
  "Execution not found": "未找到执行记录",
  "No compensation execution matches {id}.": "没有与 {id} 匹配的补偿执行记录。",
  "Prepare compensation": "准备补偿",
  "Refreshing state": "正在刷新状态",
  "Already succeeded": "已经成功",
  "Retry limit reached": "已达到重试上限",
  "Refreshing current execution state.": "正在刷新当前执行状态。",
  "This execution has already succeeded.": "此执行记录已成功。",
  "Retry limit reached; force prepare remains available.":
    "已达到重试上限；仍可使用强制准备。",
  "Compensation prepared": "补偿已准备",
  "Prepare failed": "准备失败",
  "Compensation force prepared": "补偿已强制准备",
  "Force prepare failed": "强制准备失败",
  "Unable to copy execution ID": "无法复制执行 ID",
  "Execution ID copied": "执行 ID 已复制",
  "More actions": "更多操作",
  "Force prepare": "强制准备",
  "Copy execution ID": "复制执行 ID",
  "Force prepare this execution?": "强制准备此执行记录？",
  "This bypasses the retry limit for {id}. The server still validates the current execution state. Use it only after verifying the failure context.":
    "这将绕过 {id} 的重试上限。服务端仍会验证当前执行状态，请仅在确认失败上下文后使用。",
  Cancel: "取消",
  "Retry specification updated": "重试规格已更新",
  "Failed to apply retry specification": "应用重试规格失败",
  "Max retries": "最大重试次数",
  "Min backoff (s)": "最小退避时间（秒）",
  "Execution timeout (s)": "执行超时（秒）",
  "Enter a duration": "请输入时长",
  "Applying…": "正在应用…",
  "Apply retry spec": "应用重试规格",
  "Function updated": "函数已更新",
  "Failed to change function": "变更函数失败",
  "Context name": "上下文名称",
  "Function name": "函数名称",
  "Function kind": "函数类型",
  "Saving…": "正在保存…",
  "Save function": "保存函数",
  "Recoverability updated": "可恢复性已更新",
  "Failed to update recoverability": "更新可恢复性失败",
  "Change recoverability?": "变更可恢复性？",
  "This changes execution eligibility from {from} to {to}.":
    "这会将执行资格从{from}变更为{to}。",
  "Confirm change": "确认变更",
  "Apply retry specification": "应用重试规格",
  "Tune retry limits and timing for this execution.": "调整此执行记录的重试上限和时间。",
  "Recovery status": "恢复状态",
  "Retry eligibility and timing": "重试资格与时间",
  "Edit retry specification": "编辑重试规格",
  "Retry progress": "重试进度",
  "{current} of {total}": "{current} / {total}",
  "Last: {date}": "上次：{date}",
  "Next retry at": "下次重试时间",
  Retryable: "可重试",
  "Min backoff": "最小退避时间",
  Timeout: "超时时间",
  "Change function": "变更函数",
  "Update the handler identity for this failed execution.": "更新此失败执行记录的处理器标识。",
  "Execution context": "执行上下文",
  "Handler and source event identifiers": "处理器和源事件标识",
  Processor: "处理器",
  Aggregate: "聚合根",
  Tenant: "租户",
  "Event version": "事件版本",
  "Edit function": "编辑函数",
  "Failure summary": "失败摘要",
  "Last failure summary": "最近一次失败摘要",
  "Last failure": "最近一次失败",
  "Most recent recorded failure": "最近记录的失败",
  "Current processing failure": "当前处理失败",
  "Succeeded at": "成功时间",
  "Failed at": "失败时间",
  "Stack trace": "堆栈跟踪",
  "Unable to change fullscreen mode": "无法切换全屏模式",
  "Unable to copy stack trace": "无法复制堆栈跟踪",
  "Stack trace copied": "堆栈跟踪已复制",
  "Collapse {title}": "收起{title}",
  "Expand {title}": "展开{title}",
  "Copy stack trace": "复制堆栈跟踪",
  "Exit fullscreen": "退出全屏",
  "Open fullscreen": "进入全屏",
  "Search stack trace": "搜索堆栈跟踪",
  "Search stack trace…": "搜索堆栈跟踪…",
  "{count} match": "{count} 个匹配项",
  "{count} matches": "{count} 个匹配项",
  "Disable line wrapping": "关闭自动换行",
  "Enable line wrapping": "启用自动换行",
  "Stack trace content": "堆栈跟踪内容",
  "Unable to copy {label}": "无法复制{label}",
  "Copy {label}": "复制{label}",
  Copied: "已复制",
  value: "内容",
  "execution ID": "执行 ID",
  "event ID": "事件 ID",
  "aggregate ID": "聚合根 ID",
  History: "历史记录",
  "EventStream lifecycle records, newest first": "EventStream 生命周期记录，最新的在前",
  "Refresh history": "刷新历史记录",
  "Collapse history": "收起历史记录",
  "Expand history": "展开历史记录",
  Hide: "隐藏",
  View: "查看",
  "Loading execution history": "正在加载执行历史",
  "Failed to load history": "加载历史记录失败",
  "Retry history": "重试加载历史记录",
  "History unavailable": "历史记录不可用",
  "The configured event storage did not expose EventStream records for this existing execution. Verify that it supports EventStream queries.":
    "当前事件存储未提供此执行记录的 EventStream 数据，请确认其支持 EventStream 查询。",
  "Failed to load history page": "加载历史页失败",
  "Showing page {page}. {message}": "当前显示第 {page} 页。{message}",
  "Loading history page": "正在加载历史页",
  "Previous history page": "上一页历史记录",
  "Next history page": "下一页历史记录",
  "Event stream version {version}": "事件流版本 {version}",
  "Version {version}": "版本 {version}",
  "{count} event": "{count} 个事件",
  "{count} events": "{count} 个事件",
  "Stream {id}": "事件流 {id}",
  "revision {revision}": "修订版本 {revision}",
  "Event payload": "事件载荷",
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
  "Retry distribution is truncated and is not charted.": "重试分布数据已截断，因此不绘制图表。",
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
  "Failure inflow (new failures)": "失败流入（新增失败）",
  " — daily trend": " — 每日趋势",
  "{count} new failures": "新增失败 {count} 条",
  "Outcome flow (total in selected range)": "结果流（所选范围内总计）",
  "Outcome flow: {rows}": "结果流：{rows}",
  "Compensation outcomes data": "补偿结果数据",
  Time: "时间",
  "{key} retries": "重试 {key} 次",
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

  return (
    <I18nContext value={value}>{children}</I18nContext>
  );
}

export function useI18n() {
  return useContext(I18nContext);
}
