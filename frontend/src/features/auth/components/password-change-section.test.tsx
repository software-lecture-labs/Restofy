import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { ApiError } from "@/shared/lib/api";

const api = vi.hoisted(() => ({ getSession: vi.fn(), refresh: vi.fn(), changePassword: vi.fn() }));

vi.mock("@/shared/lib/theme/actions", () => ({ setTheme: vi.fn() }));
vi.mock("../api/auth-api", () => api);

import { PasswordChangeSection } from "./password-change-section";

const CURRENT = "Current-password-01";
const NEXT = "Chosen-by-member-0042";
const onSignedOut = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  api.getSession.mockResolvedValue({ status: "AUTHENTICATED", role: "WAITER" });
  api.changePassword.mockResolvedValue({ status: "AUTHENTICATED", role: "WAITER" });
});

function fill(current: string, next: string, confirmation = next) {
  renderWithIntl(<PasswordChangeSection onSignedOut={onSignedOut} />);
  fireEvent.click(screen.getByRole("button", { name: "Parolanı değiştir" }));
  fireEvent.change(screen.getByLabelText("Mevcut parola"), { target: { value: current } });
  fireEvent.change(screen.getByLabelText("Yeni parola"), { target: { value: next } });
  fireEvent.change(screen.getByLabelText("Yeni parola (tekrar)"), { target: { value: confirmation } });
  fireEvent.click(screen.getByRole("button", { name: "Parolayı kaydet" }));
}

describe("PasswordChangeSection", () => {
  it("sends both passwords with the locale and says the other sessions ended", async () => {
    fill(CURRENT, NEXT);

    expect(await screen.findByText("Parolan güncellendi. Diğer cihazlardaki oturumların kapatıldı.")).toBeInTheDocument();
    expect(api.changePassword).toHaveBeenCalledWith(CURRENT, NEXT, "tr");
    expect(screen.queryByLabelText("Mevcut parola")).not.toBeInTheDocument();
  });

  it("checks the fields before it asks the Auth service", () => {
    fill(CURRENT, CURRENT);

    expect(screen.getByText("Yeni parola mevcut paroladan farklı olmalı.")).toBeInTheDocument();
    expect(api.changePassword).not.toHaveBeenCalled();
  });

  it("names a refused current password", async () => {
    api.changePassword.mockRejectedValue(new ApiError(400));
    fill("Wrong-password-000", NEXT);

    expect(await screen.findByText("Mevcut parola hatalı ya da yeni parola kurallara uymuyor.")).toBeInTheDocument();
    expect(onSignedOut).not.toHaveBeenCalled();
  });

  it("renews an expired access cookie first and leaves when the session is gone", async () => {
    api.getSession.mockRejectedValue(new ApiError(401));
    api.refresh.mockRejectedValue(new ApiError(401));
    fill(CURRENT, NEXT);

    await waitFor(() => expect(onSignedOut).toHaveBeenCalled());
    expect(api.changePassword).not.toHaveBeenCalled();
  });
});
