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
    product: "Wow Ops",
    documentTitle: "Wow Ops Console",
    language: "Language",
    caller: "Caller",
    signedOut: "No caller",
    callerTitle: "Local run: who is calling",
    callerDescription:
      "Only a development run of the ops service (a dev profile) reads the caller from request headers, standing in for CoSec: Wow-User names the operator, Wow-Tenant its tenant, Wow-Roles its roles. Reading the entries needs the role ops.viewer or ops.operator, acting on them ops.operator.",
    signInTitle: "Sign in with CoSec",
    signInDescription:
      "Paste an access token your CoSec issued. The CoSec gateway in front of the ops service verifies it and forwards the operator it names: its tenant, its id and its roles. Reading the entries needs the role ops.viewer or ops.operator, acting on them ops.operator.",
    signedOutExpired: "Your CoSec access token has expired: sign in again.",
    signedOutRefused:
      "Your CoSec access token was refused (401): sign in with another.",
    signedOutHeaders:
      "This ops service is not a development run: it reads no caller from request headers. Sign in with a CoSec access token.",
    token: "Access token",
    signIn: "Sign in",
    tokenRefused: "This is not a CoSec access token, or it has expired.",
    signedInAs: "Signed in as {user} with a CoSec access token.",
    signOutToken: "Sign out",
    user: "User",
    tenant: "Tenant",
    roles: "Roles",
    rolesHint: "Comma-separated, e.g. ops.operator",
    save: "Use this caller",
    cancel: "Cancel",
    signOut: "Clear",
    needCaller:
      "Sign in to read the parked entries, or name a caller for a local run: the ops service answers an anonymous request with 401.",
    readOnly:
      "This caller has no ops.operator role: it reads the entries, and the service refuses its actions.",
    noReader:
      "This caller has neither ops.viewer nor ops.operator: the service shows it no entry.",
    cause: "Cause",
    owner: "Owning aggregate",
    attempts: "Retries",
    retrySpec: "Retry spec",
    labelled: "{label}: {value}",
    request: "Operator request",
    refusal: "Refused by the owning service",
    refusalHint:
      "The owning service did not run the operator's last request; ask again once its cause is fixed.",
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
    historyRetryRequested: "Retry requested",
    historySkipRequested: "Skip requested",
    historyMarkRecoverableRequested: "Recoverability mark requested",
    historyApplyRetrySpecRequested: "Retry spec requested",
    historyActionRefused: "Refused by the owning service",
    historyResolved: "Resolved",
    historyUnknown: "Event",
  },
  "zh-CN": {
    product: "Wow 运维",
    documentTitle: "Wow 运维控制台",
    language: "语言",
    caller: "调用者",
    signedOut: "未指定调用者",
    callerTitle: "本地运行：调用者",
    callerDescription:
      "只有以开发模式运行的运维服务（dev profile）才从请求头读取调用者（代替 CoSec）：Wow-User 为操作员，Wow-Tenant 为其租户，Wow-Roles 为其角色。查看条目需要 ops.viewer 或 ops.operator 角色，操作条目需要 ops.operator。",
    signInTitle: "使用 CoSec 登录",
    signInDescription:
      "粘贴 CoSec 签发的访问令牌。运维服务前的 CoSec 网关验证令牌，并转发它所指的操作员：租户、标识与角色。查看条目需要 ops.viewer 或 ops.operator 角色，操作条目需要 ops.operator。",
    signedOutExpired: "CoSec 访问令牌已过期，请重新登录。",
    signedOutRefused:
      "CoSec 访问令牌被拒绝（401），请换一个登录。",
    signedOutHeaders:
      "这个运维服务不是开发模式运行，不从请求头读取调用者。请使用 CoSec 访问令牌登录。",
    token: "访问令牌",
    signIn: "登录",
    tokenRefused: "这不是 CoSec 访问令牌，或已过期。",
    signedInAs: "已使用 CoSec 访问令牌登录：{user}。",
    signOutToken: "退出登录",
    user: "用户",
    tenant: "租户",
    roles: "角色",
    rolesHint: "逗号分隔，例如 ops.operator",
    save: "使用此调用者",
    cancel: "取消",
    signOut: "清除",
    needCaller:
      "请先登录，或为本地运行指定调用者：运维服务对匿名请求返回 401。",
    readOnly:
      "此调用者没有 ops.operator 角色：可以查看条目，但服务会拒绝其操作。",
    noReader:
      "此调用者既没有 ops.viewer 也没有 ops.operator 角色：服务不向其展示任何条目。",
    cause: "原因",
    owner: "所属聚合",
    attempts: "重试",
    retrySpec: "重试规格",
    labelled: "{label}：{value}",
    request: "操作员请求",
    refusal: "所属服务已拒绝",
    refusalHint: "所属服务没有执行操作员的上一个请求；排除原因后可再次请求。",
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
    historyParked: "已停放",
    historyParkedAgain: "再次停放",
    historyRetried: "已记录重试",
    historyUpdated: "记录已更新",
    historyRetryRequested: "已请求重试",
    historySkipRequested: "已请求跳过",
    historyMarkRecoverableRequested: "已请求标记可恢复性",
    historyApplyRetrySpecRequested: "已请求重试规格",
    historyActionRefused: "所属服务已拒绝",
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

/**
 * The language kept on this machine, else the first of the browser's this
 * console speaks, in the browser's order: `en-US, zh-CN` is English, as its
 * reader put it first.
 */
export function resolveLocale(
  stored: string | null,
  languages: readonly string[],
): Locale {
  if (stored && (LOCALES as readonly string[]).includes(stored))
    return stored as Locale;
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
  // The page says which language it is in, for a screen reader's voice and
  // a browser's translation offer, and names itself in it.
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
