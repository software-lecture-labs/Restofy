import { describe, expect, it } from "vitest";
import en from "./messages/en.json";
import tr from "./messages/tr.json";

function keyPaths(value: unknown, prefix = ""): string[] {
  if (typeof value !== "object" || value === null) return [prefix];
  return Object.entries(value).flatMap(([key, child]) =>
    keyPaths(child, prefix ? `${prefix}.${key}` : key),
  );
}

describe("translations", () => {
  it("define the same keys in Turkish and English", () => {
    expect(keyPaths(en).sort()).toEqual(keyPaths(tr).sort());
  });

  it("have no empty text", () => {
    for (const messages of [tr, en]) {
      const empty = keyPaths(messages).filter((path) => {
        const text = path.split(".").reduce<unknown>(
          (node, key) => (node as Record<string, unknown>)[key],
          messages,
        );
        return typeof text !== "string" || text.trim() === "";
      });
      expect(empty).toEqual([]);
    }
  });
});
