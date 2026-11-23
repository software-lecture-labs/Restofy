import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AuthBackground } from "./auth-background";

describe("login background", () => {
  it("is decorative and hidden from assistive technology", () => {
    const { container } = render(<AuthBackground />);
    expect(container.firstElementChild).toHaveAttribute("aria-hidden", "true");
  });

  it("never captures pointer events, so the form stays clickable", () => {
    const { container } = render(<AuthBackground />);
    expect(container.firstElementChild).toHaveClass("pointer-events-none");
  });
});
