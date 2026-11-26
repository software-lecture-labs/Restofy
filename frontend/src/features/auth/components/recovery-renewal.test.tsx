import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { ApiError } from "@/shared/lib/api";

const api = vi.hoisted(() => ({ getSession: vi.fn(), refresh: vi.fn(), regenerateRecoveryCodes: vi.fn() }));

vi.mock("@/shared/lib/theme/actions", () => ({ setTheme: vi.fn() }));
vi.mock("../api/auth-api", () => api);

import { RecoveryRenewal } from "./recovery-renewal";

const NEW_CODES = ["aaaa-bbbb-cccc-dddd-eeee", "ffff-gggg-hhhh-iiii-jjjj"];
const onSignedOut = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  api.getSession.mockResolvedValue({ status: "AUTHENTICATED", role: "ADMIN" });
  api.regenerateRecoveryCodes.mockResolvedValue(NEW_CODES);
});

function openForm() {
  renderWithIntl(<RecoveryRenewal onSignedOut={onSignedOut} />);
  fireEvent.click(screen.getByRole("button", { name: "Kurtarma kodlarını yenile" }));
}

function fill(password: string, code: string) {
  fireEvent.change(screen.getByLabelText("Parolan"), { target: { value: password } });
  fireEvent.change(screen.getByLabelText(/^(Doğrulama|Kurtarma) kodu$/), { target: { value: code } });
  fireEvent.click(screen.getByRole("button", { name: "Yeni kodları al" }));
}

describe("RecoveryRenewal", () => {
  it("sends the password with the app code and shows the new codes once", async () => {
    openForm();
    fill("secret-pw", "123456");

    expect(await screen.findByText(NEW_CODES[0])).toBeInTheDocument();
    expect(api.regenerateRecoveryCodes).toHaveBeenCalledWith("secret-pw", { totpCode: "123456" });
    expect(screen.queryByLabelText("Parolan")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Kaydettim, devam et" }));
    expect(screen.queryByText(NEW_CODES[0])).not.toBeInTheDocument();
  });

  it("can use one of the current recovery codes instead of the app code", async () => {
    openForm();
    fireEvent.click(screen.getByRole("button", { name: "Kurtarma kodu kullan" }));
    fill("secret-pw", " abcd-efgh-ijkl-mnop-qrst ");

    await screen.findByText(NEW_CODES[0]);
    expect(api.regenerateRecoveryCodes).toHaveBeenCalledWith("secret-pw", { recoveryCode: "abcd-efgh-ijkl-mnop-qrst" });
  });

  it("checks the fields before it asks the Auth service", () => {
    openForm();
    fill("", "12");

    expect(screen.getByText("Parolanı yaz.")).toBeInTheDocument();
    expect(screen.getByText("Kod biçimi geçersiz.")).toBeInTheDocument();
    expect(api.getSession).not.toHaveBeenCalled();
    expect(api.regenerateRecoveryCodes).not.toHaveBeenCalled();
  });

  it("renews an expired access cookie first, so it is not mistaken for a wrong password", async () => {
    api.getSession.mockRejectedValue(new ApiError(401));
    api.refresh.mockResolvedValue({ status: "AUTHENTICATED", role: "ADMIN" });
    openForm();
    fill("secret-pw", "123456");

    await screen.findByText(NEW_CODES[0]);
    expect(api.refresh).toHaveBeenCalled();
  });

  it("hands a session that cannot be renewed to the page instead of spending a guess", async () => {
    api.getSession.mockRejectedValue(new ApiError(401));
    api.refresh.mockRejectedValue(new ApiError(401));
    openForm();
    fill("secret-pw", "123456");

    await waitFor(() => expect(onSignedOut).toHaveBeenCalled());
    expect(api.regenerateRecoveryCodes).not.toHaveBeenCalled();
  });

  it.each([
    [401, "Parola ya da kod hatalı."],
    [400, "Bilgilerini kontrol edip tekrar dene."],
    [429, "Çok fazla deneme yaptın. Biraz bekleyip tekrar dene."],
    [503, "Kodlar şu an yenilenemiyor. Biraz sonra tekrar dene."],
  ])("explains a %s answer and keeps the form open", async (status, message) => {
    api.regenerateRecoveryCodes.mockRejectedValue(new ApiError(status));
    openForm();
    fill("secret-pw", "123456");

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.getByLabelText("Parolan")).toBeInTheDocument();
  });

  it("closes the form again on cancel", () => {
    openForm();
    fireEvent.click(screen.getByRole("button", { name: "Vazgeç" }));

    expect(screen.queryByLabelText("Parolan")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Kurtarma kodlarını yenile" })).toBeInTheDocument();
  });
});
