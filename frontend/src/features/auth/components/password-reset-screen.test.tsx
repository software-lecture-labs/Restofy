import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { ApiError } from "@/shared/lib/api";

const api = vi.hoisted(() => ({
  requestPasswordReset: vi.fn(),
  verifyPasswordResetCode: vi.fn(),
  confirmPasswordReset: vi.fn(),
}));

vi.mock("@/shared/lib/theme/actions", () => ({ setTheme: vi.fn() }));
vi.mock("../api/auth-api", () => api);

import { PasswordResetScreen } from "./password-reset-screen";

const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const click = (name: string) => fireEvent.click(screen.getByRole("button", { name }));
const heading = (name: string) => screen.findByRole("heading", { level: 1, name });

async function reachCodeStep() {
  api.requestPasswordReset.mockResolvedValue(undefined);
  type("E-posta", "admin@restofy.com");
  type("Doğrulama uygulamasındaki kod", "123456");
  click("Kod gönder");
  await heading("E-postanı kontrol et");
}

async function reachPasswordStep() {
  await reachCodeStep();
  api.verifyPasswordResetCode.mockResolvedValue(undefined);
  type("E-postadaki kod", "654321");
  click("Doğrula");
  await heading("Yeni parolanı belirle");
}

beforeEach(() => vi.clearAllMocks());

describe("password reset screen", () => {
  it("starts on the request step inside the shared auth frame", () => {
    renderWithIntl(<PasswordResetScreen />);
    expect(screen.getByRole("heading", { level: 1, name: "Parolanı sıfırla" })).toBeInTheDocument();
    expect(screen.getAllByAltText("Restofy")).toHaveLength(2);
    expect(screen.getByRole("link", { name: "Girişe dön" })).toHaveAttribute("href", "/login");
    expect(screen.queryByRole("button", { name: "Baştan başla" })).not.toBeInTheDocument();
  });

  it("walks all three steps and ends on the confirmation", async () => {
    renderWithIntl(<PasswordResetScreen />);
    await reachPasswordStep();

    api.confirmPasswordReset.mockResolvedValue(undefined);
    type("Yeni parola", "bu-parola-yeterince-uzun");
    type("Yeni parola (tekrar)", "bu-parola-yeterince-uzun");
    click("Parolayı kaydet");

    expect(await heading("Parolan güncellendi")).toBeInTheDocument();
    expect(api.requestPasswordReset).toHaveBeenCalledWith("admin@restofy.com", "123456", "tr");
    expect(api.verifyPasswordResetCode).toHaveBeenCalledWith("654321");
    expect(api.confirmPasswordReset).toHaveBeenCalledWith("bu-parola-yeterince-uzun");
    expect(screen.getByRole("link", { name: "Girişe dön" })).toHaveAttribute("href", "/login");
    expect(screen.queryByRole("button", { name: "Baştan başla" })).not.toBeInTheDocument();
  });

  it("shows the e-mail step hint that says the code only comes when the data is right", async () => {
    renderWithIntl(<PasswordResetScreen />);
    await reachCodeStep();
    expect(screen.getByText(/Bilgilerin doğruysa e-postana 6 haneli bir kod gönderdik/)).toBeInTheDocument();
  });

  it("names a rejected request and stays on the first step", async () => {
    renderWithIntl(<PasswordResetScreen />);
    api.requestPasswordReset.mockRejectedValue(new ApiError(429));
    type("E-posta", "admin@restofy.com");
    type("Doğrulama uygulamasındaki kod", "123456");
    click("Kod gönder");

    expect(await screen.findByRole("alert")).toHaveTextContent("Çok fazla deneme yaptın");
    expect(screen.getByRole("heading", { level: 1, name: "Parolanı sıfırla" })).toBeInTheDocument();
  });

  it("offers to start over from the code step and the password step", async () => {
    renderWithIntl(<PasswordResetScreen />);
    await reachCodeStep();
    click("Baştan başla");
    expect(await heading("Parolanı sıfırla")).toBeInTheDocument();

    await reachPasswordStep();
    click("Baştan başla");
    expect(await heading("Parolanı sıfırla")).toBeInTheDocument();
  });

  it("starts over with a message when the reset has expired", async () => {
    renderWithIntl(<PasswordResetScreen />);
    await reachPasswordStep();

    api.confirmPasswordReset.mockRejectedValue(new ApiError(401));
    type("Yeni parola", "bu-parola-yeterince-uzun");
    type("Yeni parola (tekrar)", "bu-parola-yeterince-uzun");
    click("Parolayı kaydet");

    expect(await heading("Parolanı sıfırla")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("İşlemin süresi doldu"));
  });
});
