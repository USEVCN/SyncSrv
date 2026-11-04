import { GlobalOutlined } from "@ant-design/icons";
import type { LoginLocale } from "./loginLocale";

export function AuthLanguageSwitch({ locale, onChange }: {
  locale: LoginLocale; onChange: (locale: LoginLocale) => void;
}) {
  return <div className="auth-language-switch auth-login-language" role="group"
    aria-label={locale === "en" ? "Interface language" : "界面语言"}>
    <GlobalOutlined aria-hidden="true" />
    <button type="button" lang="zh-CN" aria-label="切换为中文" aria-pressed={locale === "zh-CN"}
      onClick={() => onChange("zh-CN")}>中文</button>
    <span aria-hidden="true" className="auth-language-divider" />
    <button type="button" lang="en" aria-label="Switch to English" aria-pressed={locale === "en"}
      onClick={() => onChange("en")}>EN</button>
  </div>;
}
