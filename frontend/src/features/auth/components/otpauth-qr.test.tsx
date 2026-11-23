import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OtpauthQr } from "./otpauth-qr";

const uri = "otpauth://totp/Restofy:admin?secret=JBSWY3DPEHPK3PXP&issuer=Restofy";

describe("otpauth QR code", () => {
  it("draws an accessible SVG", () => {
    render(<OtpauthQr value={uri} label="QR kodu" />);
    const svg = screen.getByRole("img", { name: "QR kodu" });
    expect(svg.tagName.toLowerCase()).toBe("svg");
    expect(svg.querySelector("path")?.getAttribute("d")).toMatch(/^M\d+ \d+h1v1h-1z/);
  });

  it("keeps the quiet zone of four modules around a square code", () => {
    render(<OtpauthQr value={uri} label="QR kodu" />);
    const [, , width, height] = screen.getByRole("img").getAttribute("viewBox")!.split(" ").map(Number);
    expect(width).toBe(height);
    // QR version 1 is 21 modules wide, so with a quiet zone on both sides the size is at least 29.
    expect(width).toBeGreaterThanOrEqual(29);
    const starts = [...screen.getByRole("img").querySelector("path")!.getAttribute("d")!.matchAll(/M(\d+) (\d+)h/g)];
    expect(Math.min(...starts.map((match) => Number(match[1])))).toBeGreaterThanOrEqual(4);
    expect(Math.max(...starts.map((match) => Number(match[1])))).toBeLessThan(width - 4);
  });

  it("draws the same code for the same address and a different one for another", () => {
    const first = render(<OtpauthQr value={uri} label="a" />);
    const pathA = first.container.querySelector("path")!.getAttribute("d");
    first.unmount();
    const second = render(<OtpauthQr value={uri} label="a" />);
    expect(second.container.querySelector("path")!.getAttribute("d")).toBe(pathA);
    second.unmount();
    const third = render(<OtpauthQr value={`${uri}&x=1`} label="a" />);
    expect(third.container.querySelector("path")!.getAttribute("d")).not.toBe(pathA);
  });

  it("draws nothing when the value cannot be encoded", () => {
    const { container } = render(<OtpauthQr value={"x".repeat(5000)} label="QR kodu" />);
    expect(container).toBeEmptyDOMElement();
  });
});
