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
  useMemo,
  useState,
  type PropsWithChildren,
} from "react";
import { LOCALES, type Locale } from "@/views/words.ts";

export type { Locale } from "@/views/words.ts";

/** The shell's own words; the definitions' are under `views/`. */
const SHELL = {
  en: {
    product: "Wow Ops",
    language: "Language",
    caller: "Caller",
    signedOut: "No caller",
    callerTitle: "Who is calling",
    callerDescription:
      "The ops service reads the caller from request headers, standing in for CoSec: X-User names the operator, X-Tenant its tenant, X-Role its roles. Acting on an entry needs the role ops.operator.",
    user: "User",
    tenant: "Tenant",
    roles: "Roles",
    rolesHint: "Comma-separated, e.g. ops.operator",
    save: "Use this caller",
    cancel: "Cancel",
    signOut: "Clear",
    needCaller:
      "Name a caller to read the parked entries: the ops service answers an anonymous request with 401.",
    readOnly:
      "This caller has no ops.operator role: it reads the entries, and the service refuses its actions.",
    cause: "Cause",
    owner: "Owning aggregate",
    attempts: "Attempts",
    retrySpec: "Retry spec",
    request: "Operator request",
    resolution: "Resolution",
    times: "Times",
    retriesOf: "{retries} of {max}",
    applied: "Applied by an operator",
    pendingRequest:
      "Sent to the owning service; its next record shows what came of it.",
    none: "—",
    position: "From position",
    copyCause: "Copy the cause",
    history: "History",
    historyLoading: "Reading the entry's history…",
    historyFailed: "The history could not be read: {message}",
    historyEmpty: "No history recorded.",
    historyVersion: "v{version}",
    historyBy: "by {operator}",
    historyParked: "Parked",
    historyParkedAgain: "Parked again",
    historyRetried: "Retry recorded",
    historyUpdated: "Record updated",
    historyResumeRequested: "Resume requested",
    historySkipRequested: "Skip requested",
    historyMarkRecoverableRequested: "Recoverability mark requested",
    historyApplyRetrySpecRequested: "Retry spec requested",
    historyResolved: "Resolved",
    historyUnknown: "Event",
  },
  "zh-CN": {
    product: "Wow 运维",
    language: "语言",
    caller: "调用者",
    signedOut: "未指定调用者",
    callerTitle: "调用者",
    callerDescription:
      "运维服务从请求头读取调用者（代替 CoSec）：X-User 为操作员，X-Tenant 为其租户，X-Role 为其角色。操作条目需要 ops.operator 角色。",
    user: "用户",
    tenant: "租户",
    roles: "角色",
    rolesHint: "逗号分隔，例如 ops.operator",
    save: "使用此调用者",
    cancel: "取消",
    signOut: "清除",
    needCaller: "请先指定调用者：运维服务对匿名请求返回 401。",
    readOnly:
      "此调用者没有 ops.operator 角色：可以查看条目，但服务会拒绝其操作。",
    cause: "原因",
    owner: "所属聚合",
    attempts: "尝试",
    retrySpec: "重试规格",
    request: "操作员请求",
    resolution: "解决",
    times: "时间",
    retriesOf: "{retries} / {max}",
    applied: "由操作员应用",
    pendingRequest: "已发送给所属服务；其下一次记录会反映结果。",
    none: "—",
    position: "起始位置",
    copyCause: "复制原因",
    history: "历史",
    historyLoading: "正在读取条目历史…",
    historyFailed: "无法读取历史：{message}",
    historyEmpty: "没有记录历史。",
    historyVersion: "v{version}",
    historyBy: "由 {operator}",
    historyParked: "已搁置",
    historyParkedAgain: "再次搁置",
    historyRetried: "已记录重试",
    historyUpdated: "记录已更新",
    historyResumeRequested: "已请求恢复",
    historySkipRequested: "已请求跳过",
    historyMarkRecoverableRequested: "已请求标记可恢复性",
    historyApplyRetrySpecRequested: "已请求重试规格",
    historyResolved: "已解决",
    historyUnknown: "事件",
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

const STORAGE_KEY = "wow-ops-console.locale";

/** The language kept on this machine, else the browser's. */
export function resolveLocale(
  stored: string | null,
  languages: readonly string[],
): Locale {
  if (stored && (LOCALES as readonly string[]).includes(stored))
    return stored as Locale;
  return languages.some((language) => language.toLowerCase().startsWith("zh"))
    ? "zh-CN"
    : "en";
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
