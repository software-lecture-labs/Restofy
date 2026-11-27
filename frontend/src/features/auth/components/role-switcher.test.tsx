import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { RoleSwitcher } from "./role-switcher";

describe("role switcher", () => {
  it("lists the three roles and marks the current one", () => {
    renderWithIntl(<RoleSwitcher value="WAITER" onChange={vi.fn()} />);
    expect(screen.getByRole("tablist", { name: "Giriş rolü" })).toBeInTheDocument();
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "Yönetici",
      "Garson",
      "Mutfak",
    ]);
    expect(screen.getByRole("tab", { name: "Garson" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Mutfak" })).toHaveAttribute("aria-selected", "false");
  });

  it("only the selected tab is in the tab order", () => {
    renderWithIntl(<RoleSwitcher value="ADMIN" onChange={vi.fn()} />);
    expect(screen.getByRole("tab", { name: "Yönetici" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("tab", { name: "Mutfak" })).toHaveAttribute("tabindex", "-1");
  });

  it("reports a click on another role", () => {
    const onChange = vi.fn();
    renderWithIntl(<RoleSwitcher value="ADMIN" onChange={onChange} />);
    fireEvent.click(screen.getByRole("tab", { name: "Mutfak" }));
    expect(onChange).toHaveBeenCalledWith("KITCHEN");
  });

  it.each([
    ["ArrowRight", "ADMIN", "WAITER"],
    ["ArrowRight", "KITCHEN", "ADMIN"],
    ["ArrowLeft", "WAITER", "ADMIN"],
    ["ArrowLeft", "ADMIN", "KITCHEN"],
    ["Home", "KITCHEN", "ADMIN"],
    ["End", "ADMIN", "KITCHEN"],
  ] as const)("%s moves from %s to %s", (key, from, to) => {
    const onChange = vi.fn();
    renderWithIntl(<RoleSwitcher value={from} onChange={onChange} />);
    fireEvent.keyDown(screen.getByRole("tab", { selected: true }), { key });
    expect(onChange).toHaveBeenCalledWith(to);
  });

  it("ignores other keys", () => {
    const onChange = vi.fn();
    renderWithIntl(<RoleSwitcher value="ADMIN" onChange={onChange} />);
    fireEvent.keyDown(screen.getByRole("tab", { selected: true }), { key: "a" });
    expect(onChange).not.toHaveBeenCalled();
  });

  it("moves focus to the newly selected tab", () => {
    renderWithIntl(<RoleSwitcher value="ADMIN" onChange={vi.fn()} />);
    fireEvent.keyDown(screen.getByRole("tab", { selected: true }), { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: "Garson" })).toHaveFocus();
  });
});
