import { fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";

const { setTheme } = vi.hoisted(() => ({ setTheme: vi.fn() }));
vi.mock("@/shared/lib/theme/actions", () => ({ setTheme }));

import { ThemeToggle } from "./theme-toggle";

function stubSystemDark(dark: boolean) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockReturnValue({ matches: dark }),
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  delete document.documentElement.dataset.theme;
});
afterEach(() => vi.unstubAllGlobals());

describe("theme toggle", () => {
  it("has an accessible name", () => {
    renderWithIntl(<ThemeToggle />);
    expect(screen.getByRole("button", { name: /tema/i })).toBeInTheDocument();
  });

  it("switches an explicit light theme to dark and saves it", () => {
    document.documentElement.dataset.theme = "light";
    renderWithIntl(<ThemeToggle />);
    fireEvent.click(screen.getByRole("button"));
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(setTheme).toHaveBeenCalledWith("dark");
  });

  it("switches an explicit dark theme to light", () => {
    document.documentElement.dataset.theme = "dark";
    renderWithIntl(<ThemeToggle />);
    fireEvent.click(screen.getByRole("button"));
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(setTheme).toHaveBeenCalledWith("light");
  });

  it("follows the system preference when no theme is saved", () => {
    stubSystemDark(true);
    renderWithIntl(<ThemeToggle />);
    fireEvent.click(screen.getByRole("button"));
    expect(setTheme).toHaveBeenCalledWith("light");
  });

  it("goes dark when the system is light and nothing is saved", () => {
    stubSystemDark(false);
    renderWithIntl(<ThemeToggle />);
    fireEvent.click(screen.getByRole("button"));
    expect(setTheme).toHaveBeenCalledWith("dark");
  });
});
