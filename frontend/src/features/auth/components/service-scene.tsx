"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import styles from "./login.module.css";

export function ServiceScene() {
  const t = useTranslations("Login");
  const [paused, setPaused] = useState(false);
  return (
    <aside className={`${styles.scene} ${paused ? styles.paused : ""}`} aria-label={t("sceneLabel")}>
      <div className={styles.sceneTop}><span className={styles.sceneDot} />{t("sceneEyebrow")}</div>
      <div className={styles.sceneCopy}>
        <h2>{t("sceneTitle")}<br /><span>{t("sceneTitleAccent")}</span></h2>
        <p>{t("sceneDescription")}</p>
      </div>
      <div className={styles.artwork} aria-hidden="true">
        <div className={styles.orbit} />
        <svg viewBox="0 0 620 530" fill="none" className={styles.servingArt}>
          <defs>
            <linearGradient id="plate" x1="160" y1="300" x2="430" y2="460" gradientUnits="userSpaceOnUse"><stop stopColor="#fffdf3" /><stop offset=".53" stopColor="#ecdfc9" /><stop offset="1" stopColor="#fff9ed" /></linearGradient>
            <linearGradient id="dome" x1="200" y1="150" x2="430" y2="370" gradientUnits="userSpaceOnUse"><stop stopColor="#fffaf0" /><stop offset=".4" stopColor="#f8e7cd" /><stop offset="1" stopColor="#ba7644" /></linearGradient>
            <linearGradient id="metal" x1="80" y1="250" x2="140" y2="480" gradientUnits="userSpaceOnUse"><stop stopColor="#ffecd0" /><stop offset=".48" stopColor="#d8a36c" /><stop offset=".52" stopColor="#fff7e1" /><stop offset="1" stopColor="#d5a16f" /></linearGradient>
            <filter id="shadow" x="0" y="0" width="620" height="530" filterUnits="userSpaceOnUse"><feGaussianBlur stdDeviation="16" /></filter>
          </defs>
          <ellipse cx="320" cy="444" rx="190" ry="32" fill="#692500" opacity=".17" filter="url(#shadow)" />
          <g className={styles.plate}>
            <ellipse cx="310" cy="378" rx="212" ry="82" fill="#b87b47" />
            <ellipse cx="310" cy="368" rx="214" ry="82" fill="url(#plate)" />
            <ellipse cx="310" cy="365" rx="171" ry="58" stroke="#d7c2a4" strokeWidth="2" />
            <ellipse cx="310" cy="362" rx="151" ry="47" fill="#ead8ba" />
          </g>
          <g className={styles.cloche}>
            <path d="M145 328C149 214 214 155 311 155C408 155 473 214 477 328Z" fill="url(#dome)" />
            <path d="M173 285C186 221 228 187 276 179" stroke="#fffdf5" strokeWidth="9" strokeLinecap="round" opacity=".8" />
            <ellipse cx="311" cy="328" rx="174" ry="21" fill="#d2a476" />
            <ellipse cx="311" cy="321" rx="175" ry="16" fill="#fff2da" />
            <path d="M291 156V147C291 136 300 128 311 128C322 128 331 136 331 147V156" stroke="#e9c99f" strokeWidth="11" />
            <path d="M301 144C302 137 314 135 319 142" stroke="#fff7e7" strokeWidth="4" strokeLinecap="round" />
            <rect x="280" y="239" width="63" height="52" rx="16" fill="#ee7b22" transform="rotate(-4 280 239)" />
            <rect x="300" y="255" width="23" height="22" rx="6" fill="#ffe7bd" transform="rotate(-4 300 255)" />
          </g>
          <g className={styles.leaf}>
            <path d="M449 162C462 125 494 125 511 134C505 168 481 181 449 162Z" fill="#475c36" />
            <path d="M450 163L494 140" stroke="#a1ad70" strokeWidth="2" />
            <path d="M452 166C425 149 428 125 437 111C463 123 473 145 452 166Z" fill="#66804d" />
          </g>
          <g transform="rotate(-19 92 335)" fill="url(#metal)">
            <rect x="80" y="257" width="10" height="167" rx="5" />
            <path d="M67 215H72V244H78V215H83V244H89V215H94V244H100V215H105V246C105 261 67 261 67 246Z" />
          </g>
          <g transform="rotate(19 530 335)" fill="url(#metal)">
            <rect x="524" y="287" width="10" height="142" rx="5" />
            <path d="M523 208C541 212 546 248 546 283C546 292 535 296 523 296Z" />
          </g>
          <circle cx="167" cy="143" r="6" fill="#ffe8c5" /><circle cx="473" cy="409" r="4" fill="#ffefdb" />
        </svg>
      </div>
      <div className={styles.sceneBottom}>
        <span>{t("sceneFooter")}</span>
        <button type="button" className={styles.motionToggle} onClick={() => setPaused(!paused)}
          aria-label={paused ? t("resumeMotion") : t("pauseMotion")} aria-pressed={paused}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            {paused ? <path d="M5 3l8 5-8 5V3Z" /> : <><rect x="4" y="3" width="2.5" height="10" rx="1" /><rect x="9.5" y="3" width="2.5" height="10" rx="1" /></>}
          </svg>
        </button>
      </div>
    </aside>
  );
}
