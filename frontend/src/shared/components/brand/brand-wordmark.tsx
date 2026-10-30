import styles from "./brand-wordmark.module.css";

export function BrandWordmark() {
  return (
    <span className={styles.wordmark} aria-label="Restofy">
      <span aria-hidden="true">
        {Array.from("Restofy").map((letter, index) => (
          <span className={letter === "o" ? styles.accent : undefined}
            style={{ animationDelay: `${index * 65}ms` }} key={index}>{letter}</span>
        ))}
      </span>
    </span>
  );
}
