"use client";

import { useState, type CSSProperties } from "react";
import { useTranslations } from "next-intl";
import styles from "./brand-wordmark.module.css";

export function BrandWordmark() {
  const t = useTranslations("Brand");
  const [paused, setPaused] = useState(false);
  const [disturbed, setDisturbed] = useState<number | null>(null);
  const delays = [370, 60, 640, 210, 480, 120, 790];
  return (
    <button type="button" className={`${styles.wordmark} ${paused ? styles.paused : ""}`}
      onClick={() => setPaused(!paused)} onPointerLeave={() => setDisturbed(null)} aria-pressed={paused}
      aria-label={t(paused ? "resume" : "pause")} title={t(paused ? "resume" : "pause")}>
      <span className={styles.letters} aria-hidden="true">
        {Array.from("Restofy").map((letter, index) => (
          <span key={letter}
            className={`${styles.glyph} ${letter === "o" ? styles.accent : ""} ${disturbed === index ? styles.disturbed : ""}`}
            style={{ "--signal-delay": `${delays[index]}ms` } as CSSProperties}
            onPointerEnter={() => setDisturbed(index)}>
            <span className={styles.outline}>{letter}</span>
            <span className={styles.fill}>{letter}</span>
          </span>
        ))}
      </span>
    </button>
  );
}
