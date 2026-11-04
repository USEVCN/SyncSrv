import { Form } from "antd";
import { useEffect, useState } from "react";

export type LoginLocale = "zh-CN" | "en";
export const LOGIN_LOCALE_KEY = "syncsrv:login-locale";
export const loginMessages = {
  "zh-CN": {
    account: "账号", password: "密码", signIn: "登录",
    accountRequired: "请输入账号", passwordRequired: "请输入密码",
    failed: "登录失败", diagnostics: "诊断信息", status: "HTTP 状态",
    originalMessage: "服务端原始信息",
  },
  en: {
    account: "Username", password: "Password", signIn: "Sign in",
    accountRequired: "Enter your username", passwordRequired: "Enter your password",
    failed: "Unable to sign in", diagnostics: "Diagnostics", status: "HTTP status",
    originalMessage: "Original server message",
  },
};

export function useLoginLocale() {
  const [locale, setLocale] = useState<LoginLocale>(() => {
    try { return localStorage.getItem(LOGIN_LOCALE_KEY) === "en" ? "en" : "zh-CN"; }
    catch { return "zh-CN"; }
  });
  const [form] = Form.useForm<{ username: string; password: string }>();
  useEffect(() => {
    try { localStorage.setItem(LOGIN_LOCALE_KEY, locale); } catch { /* Storage may be unavailable. */ }
    // Translate visible validation errors without resetting credentials or touching pristine fields.
    const keys = ["accountRequired", "passwordRequired"] as const;
    const invalid = (["username", "password"] as const)
      .map(name => ({ name, errors: form.getFieldError(name) }))
      .filter(field => field.errors.length).map(field => ({
      name: field.name,
      errors: field.errors.map(error => {
        const key = keys.find(key => Object.values(loginMessages).some(text => text[key] === error));
        return key ? loginMessages[locale][key] : error;
      }),
    }));
    if (invalid.length) form.setFields(invalid);
  }, [locale, form]);
  return { locale, setLocale, text: loginMessages[locale], form };
}
