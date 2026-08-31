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

import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  I18nProvider,
  resolveLocale,
  translate,
  useI18n,
} from "./i18n.tsx";

function LanguageProbe() {
  const { locale, setLocale, t } = useI18n();

  return (
    <>
      <output>{`${locale}:${t("Dashboard")}`}</output>
      <button type="button" onClick={() => setLocale("en")}>
        English
      </button>
      <button type="button" onClick={() => setLocale("zh-CN")}>
        中文
      </button>
    </>
  );
}

describe("i18n", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it("prefers a saved locale over the browser language", () => {
    expect(resolveLocale("en", ["zh-CN"])).toBe("en");
    expect(resolveLocale("zh-CN", ["en-US"])).toBe("zh-CN");
  });

  it("uses Chinese for Chinese browser language variants", () => {
    expect(resolveLocale(null, ["zh-Hans-CN", "en-US"])).toBe("zh-CN");
    expect(resolveLocale(null, ["en-US", "zh-CN"])).toBe("en");
  });

  it("translates messages and interpolates values", () => {
    expect(translate("zh-CN", "Page {page}", { page: 3 })).toBe("第 3 页");
    expect(translate("en", "Page {page}", { page: 3 })).toBe("Page 3");
  });

  it("persists language changes and updates the document language", () => {
    localStorage.setItem("wow-dashboard-locale", "zh-CN");

    render(
      <I18nProvider>
        <LanguageProbe />
      </I18nProvider>,
    );

    expect(screen.getByText("zh-CN:仪表盘")).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("zh-CN");
    expect(document.title).toBe("Wow 补偿仪表盘");

    fireEvent.click(screen.getByRole("button", { name: "English" }));

    expect(screen.getByText("en:Dashboard")).toBeInTheDocument();
    expect(localStorage.getItem("wow-dashboard-locale")).toBe("en");
    expect(document.documentElement.lang).toBe("en");
  });

  it("does not persist an automatically detected locale", () => {
    render(
      <I18nProvider>
        <LanguageProbe />
      </I18nProvider>,
    );

    expect(localStorage.getItem("wow-dashboard-locale")).toBeNull();
  });

  it("keeps rendering and switching when browser storage is unavailable", () => {
    vi.spyOn(localStorage, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(localStorage, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    render(
      <I18nProvider>
        <LanguageProbe />
      </I18nProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "中文" }));

    expect(screen.getByText("zh-CN:仪表盘")).toBeInTheDocument();
  });
});
