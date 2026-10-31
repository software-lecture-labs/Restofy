import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { beforeEach, describe, expect, it, vi } from "vitest";
import messages from "@/shared/lib/i18n/messages/tr.json";
import { ApiError } from "@/shared/lib/api/gateway";
import { setLocale } from "@/shared/lib/i18n/actions";

const { login, logout } = vi.hoisted(() => ({ login: vi.fn(), logout: vi.fn() }));
vi.mock("../api/auth-client", () => ({ authClient: { login, logout } }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/shared/lib/i18n/actions", () => ({ setLocale: vi.fn() }));
import { LoginPage } from "./login-page";

beforeEach(() => vi.clearAllMocks());
function mount() {
  render(<NextIntlClientProvider locale="tr" messages={messages} timeZone="Europe/Istanbul"><LoginPage /></NextIntlClientProvider>);
}
function submit() {
  fireEvent.change(screen.getByLabelText("E-posta adresi"), { target: { value: "staff@example.com" } });
  fireEvent.change(screen.getByLabelText("Parola", { exact: true }), { target: { value: "fixture-password" } });
  fireEvent.click(screen.getByRole("button", { name: "Giriş yap" }));
}

describe("login form", () => {
  it("has labelled fields and a working password visibility toggle", () => {
    mount();
    expect(screen.getByLabelText("Parola", { exact: true })).toHaveAttribute("type", "password");
    fireEvent.click(screen.getByRole("button", { name: "Parolayı göster" }));
    expect(screen.getByLabelText("Parola", { exact: true })).toHaveAttribute("type", "text");
    expect(screen.getByRole("button", { name: "Parolayı gizle" })).toHaveAttribute("aria-pressed", "true");
  });

  it("blocks duplicate submission while login is in flight", () => {
    login.mockReturnValue(new Promise(() => {}));
    mount(); submit();
    const button = screen.getByRole("button", { name: "Giriş yapılıyor…" });
    expect(button).toBeDisabled();
    fireEvent.submit(button.closest("form")!);
    expect(login).toHaveBeenCalledTimes(1);
  });

  it("presents a safe error, preserves editable inputs and moves focus to the error", async () => {
    login.mockRejectedValue(new ApiError(401));
    mount(); submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("E-posta veya parola hatalı");
    expect(screen.getByLabelText("E-posta adresi")).toHaveValue("staff@example.com");
    await waitFor(() => expect(screen.getByRole("alert")).toHaveFocus());
  });

  it("does not display MFA pending as a completed sign-in", async () => {
    login.mockResolvedValue({ status: "MFA_PENDING", role: "ADMIN", mfaSetupRequired: true });
    mount(); submit();
    expect(await screen.findByRole("heading", { name: "Bir adım daha." })).toBeInTheDocument();
    expect(screen.queryByText("Oturumun açıldı.")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Parola", { exact: true })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Güvenli çıkış yap" })).toBeInTheDocument();
  });

  it("lets the user pause and resume the decorative loop", () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: "Dekoratif animasyonu duraklat" }));
    expect(screen.getByRole("button", { name: "Dekoratif animasyonu sürdür" })).toHaveAttribute("aria-pressed", "true");
  });

  it("shows a confirmed staff session and returns to sign-in only after successful logout", async () => {
    login.mockResolvedValue({ status: "AUTHENTICATED", role: "WAITER" });
    logout.mockResolvedValue(undefined);
    mount(); submit();
    expect(await screen.findByRole("heading", { name: "Oturumun açıldı." })).toBeInTheDocument();
    expect(screen.getByText("Garson")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Güvenli çıkış yap" }));
    await waitFor(() => expect(screen.getByLabelText("E-posta adresi")).toHaveValue(""));
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("retains the session view if logout could not be confirmed", async () => {
    login.mockResolvedValue({ status: "AUTHENTICATED", role: "KITCHEN" });
    logout.mockRejectedValue(new ApiError(0, "network"));
    mount(); submit();
    fireEvent.click(await screen.findByRole("button", { name: "Güvenli çıkış yap" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("işlemin sonucu doğrulanamadı");
    expect(screen.getByRole("heading", { name: "Oturumun açıldı." })).toBeInTheDocument();
  });

  it.each([
    [new ApiError(400), "E-posta ve parola biçimini"],
    [new ApiError(403), "Güvenlik doğrulaması"],
    [new ApiError(409), "Oturum durumu değişti"],
    [new ApiError(429), "Çok fazla deneme"],
    [new ApiError(429, "response", 60), "60 saniye sonra"],
    [new ApiError(503), "Giriş hizmetine"],
    [new ApiError(0, "configuration"), "Giriş bağlantısı"],
  ])("distinguishes authentication failure %s without reflecting server messages", async (failure, message) => {
    login.mockRejectedValue(failure);
    mount(); submit();
    expect(await screen.findByRole("alert")).toHaveTextContent(String(message));
  });

  it("saves a supported language through the existing locale action", async () => {
    vi.mocked(setLocale).mockResolvedValue(undefined);
    mount();
    fireEvent.change(screen.getByLabelText("Dil seçimi"), { target: { value: "en" } });
    await waitFor(() => expect(setLocale).toHaveBeenCalledWith("en"));
  });
});
