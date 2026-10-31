"use client";

import { useEffect, useRef, useState, useTransition, type SubmitEvent } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { BrandWordmark } from "@/shared/components/brand/brand-wordmark";
import { ApiError } from "@/shared/lib/api/gateway";
import { setLocale } from "@/shared/lib/i18n/actions";
import type { Locale } from "@/shared/lib/i18n/config";
import { authClient, type StaffSession } from "../api/auth-client";
import { ServiceScene } from "./service-scene";
import styles from "./login.module.css";

function ArrowIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>;
}

function SessionHeading({ session }: { readonly session: StaffSession }) {
  const t = useTranslations("Login");
  const pending = session.status === "MFA_PENDING";
  let description = t("successDescription");
  if (pending) {
    description = t(session.mfaSetupRequired ? "mfaSetup" : "mfaPending");
  }
  return <>
    <h2 className={styles.title}>{t(pending ? "mfaTitle" : "successTitle")}</h2>
    <p className={styles.subtitle}>{description}</p>
  </>;
}

export function LoginPage() {
  const t = useTranslations("Login");
  const locale = useLocale();
  const router = useRouter();
  const [changingLocale, startLocaleTransition] = useTransition();
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [session, setSession] = useState<StaffSession | null>(null);
  const submitting = useRef(false);
  const errorElement = useRef<HTMLParagraphElement>(null);

  useEffect(() => { if (error) errorElement.current?.focus(); }, [error]);

  function describeError(cause: unknown): string {
    if (!(cause instanceof ApiError)) return t("error.unavailable");
    if (cause.kind === "configuration") return t("error.configuration");
    if (cause.kind === "network") return t("error.network");
    switch (cause.status) {
      case 400: return t("error.invalidInput");
      case 401: return t("error.credentials");
      case 403: return t("error.security");
      case 409: return t("error.conflict");
      case 429: return cause.retryAfter ? t("error.retryAfter", { seconds: Math.ceil(cause.retryAfter) }) : t("error.rateLimit");
      default: return t("error.unavailable");
    }
  }

  async function login(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    const form = event.currentTarget;
    const values = new FormData(form);
    const email = values.get("email");
    const password = values.get("password");
    if (typeof email !== "string" || typeof password !== "string") {
      setError(t("error.invalidInput"));
      return;
    }
    submitting.current = true;
    setBusy(true);
    setError("");
    try {
      const result = await authClient.login(email.trim(), password);
      form.reset();
      setVisible(false);
      setSession(result);
    } catch (cause) {
      setError(describeError(cause));
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  async function logout() {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError("");
    try { await authClient.logout(); setSession(null); }
    catch (cause) { setError(describeError(cause)); }
    finally { submitting.current = false; setBusy(false); }
  }

  return (
    <main className={styles.page}>
      <a className={styles.skip} href="#login-content">{t("skip")}</a>
      <section className={styles.loginSide} aria-label={t("staffArea")}>
        <div className={styles.formWrap} id="login-content">
          <h1 className={styles.brandHeading} aria-label="Restofy"><BrandWordmark /></h1>
          {session && <SessionHeading session={session} />}
          {error && <p className={styles.error} ref={errorElement} tabIndex={-1} role="alert">{error}</p>}
          {session ? (
            <div className={styles.sessionState}>
              <p className={styles.sessionLabel}>{t("accountRole")} <strong>{t(`roles.${session.role}`)}</strong></p>
              <output className={styles.sessionNotice}>{t(session.status === "MFA_PENDING" ? "mfaNotice" : "successNotice")}</output>
              <button type="button" className={styles.submit} disabled={busy} onClick={logout}>
                {busy ? t("signingOut") : t("signOut")}<ArrowIcon />
              </button>
            </div>
          ) : (
            <form onSubmit={login} className={styles.form} aria-busy={busy}>
              <div className={styles.field}>
                <label htmlFor="email">{t("email")}</label>
                <input id="email" name="email" type="email" autoComplete="username" autoCapitalize="none"
                  spellCheck={false} required maxLength={254} placeholder={t("emailPlaceholder")} readOnly={busy} />
              </div>
              <div className={styles.field}>
                <label htmlFor="password">{t("password")}</label>
                <div className={styles.passwordField}>
                  <input id="password" name="password" type={visible ? "text" : "password"} autoComplete="current-password"
                    required maxLength={72} placeholder={t("passwordPlaceholder")} readOnly={busy} />
                  <button type="button" onClick={() => setVisible(!visible)} aria-controls="password"
                    aria-label={t(visible ? "hidePassword" : "showPassword")} aria-pressed={visible}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" stroke="currentColor" strokeWidth="1.6" />
                      <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.6" />
                      {visible && <path d="m4 3 16 18" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />}
                    </svg>
                  </button>
                </div>
              </div>
              <button className={styles.submit} type="submit" disabled={busy}>
                <span>{busy ? t("signingIn") : t("signIn")}</span>
                {busy ? <span className={styles.spinner} aria-hidden="true" /> : <ArrowIcon />}
              </button>
              <p className={styles.secureNote}>
                <svg width="13" height="15" viewBox="0 0 16 18" fill="none" aria-hidden="true"><rect x="2" y="7" width="12" height="9" rx="2" stroke="currentColor" /><path d="M5 7V4a3 3 0 0 1 6 0v3" stroke="currentColor" /></svg>
                {t("secureNote")}
              </p>
            </form>
          )}
          <div className={styles.divider} />
          <details className={styles.help}>
            <summary>{t("helpTitle")}<span aria-hidden="true">+</span></summary>
            <p>{t("helpDescription")}</p>
          </details>
        </div>
        <footer className={styles.footer}>
          <span>© 2026 Restofy</span>
          <label className={styles.language}>
            <svg width="15" height="15" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="8" stroke="currentColor" /><ellipse cx="10" cy="10" rx="3.5" ry="8" stroke="currentColor" /><path d="M2 10h16" stroke="currentColor" /></svg>
            <span className={styles.srOnly}>{t("language")}</span>
            <select value={locale} disabled={changingLocale} onChange={event => {
              const next = event.target.value as Locale;
              startLocaleTransition(async () => {
                try { await setLocale(next); router.refresh(); }
                catch { setError(t("error.language")); }
              });
            }}>
              <option value="tr">Türkçe</option><option value="en">English</option>
            </select>
          </label>
        </footer>
      </section>
      <ServiceScene />
    </main>
  );
}
