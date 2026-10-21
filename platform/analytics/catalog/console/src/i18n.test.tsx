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

import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { I18nProvider, resolveLocale, translate, useI18n } from "./i18n.tsx";
import { engineMessages } from "./views/messages.ts";

function Probe() {
  const { locale, setLocale, t } = useI18n();
  return (
    <button
      type="button"
      onClick={() => setLocale(locale === "en" ? "zh-CN" : "en")}
    >
      {t("product")}
    </button>
  );
}

describe("the console's language", () => {
  it("is the one kept on this machine, else the browser's", () => {
    expect(resolveLocale("zh-CN", ["en-US"])).toBe("zh-CN");
    expect(resolveLocale("fr", ["zh-TW"])).toBe("zh-CN");
    expect(resolveLocale(null, ["en-GB"])).toBe("en");
  });

  it("fills a sentence's values, and leaves an unknown one", () => {
    expect(translate("en", "version", { version: 2 })).toBe("v2");
    expect(translate("en", "version")).toBe("v{version}");
  });

  it("changes, and is kept", () => {
    localStorage.removeItem("wow-analytics-catalog-console.locale");
    render(
      <I18nProvider>
        <Probe />
      </I18nProvider>,
    );
    const button = screen.getByRole("button");
    const before = button.textContent;
    act(() => button.click());
    expect(button.textContent).not.toBe(before);
    expect(
      localStorage.getItem("wow-analytics-catalog-console.locale"),
    ).not.toBeNull();
  });

  it("is the engine's too, with the definitions' words", () => {
    expect(engineMessages("en")["sources.title"]).toBe("Sources");
    expect(engineMessages("zh-CN")["datasets.title"]).toBe("数据集");
    expect(engineMessages("zh-CN")["datasetActions.publish"]).toBe("发布");
    expect(engineMessages("en")["sourceActions.markReviewed"]).toBe(
      "Mark reviewed",
    );
  });
});
