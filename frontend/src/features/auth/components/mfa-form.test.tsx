import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { MfaForm } from "./mfa-form";

function setup(props: Partial<React.ComponentProps<typeof MfaForm>> = {}) {
  const handlers = { onVerify: vi.fn(), onRecover: vi.fn(), onBack: vi.fn() };
  renderWithIntl(<MfaForm {...handlers} pending={false} {...props} />);
  return handlers;
}

const submit = () => fireEvent.click(screen.getByRole("button", { name: "Doğrula" }));
const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe("mfa form", () => {
  it("starts on the authenticator code with its own hint", () => {
    setup();
    expect(screen.getByLabelText("Doğrulama kodu")).toHaveAttribute("inputmode", "numeric");
    expect(screen.getByLabelText("Doğrulama kodu")).toHaveAttribute("maxlength", "6");
    expect(screen.getByText("Doğrulama uygulamandaki 6 haneli kodu yaz.")).toBeInTheDocument();
  });

  it("asks for a code and does not submit an empty one", () => {
    const { onVerify } = setup();
    submit();
    expect(screen.getByText("Kodu yaz.")).toBeInTheDocument();
    expect(onVerify).not.toHaveBeenCalled();
  });

  it("rejects a code that is not six digits", () => {
    const { onVerify } = setup();
    type("Doğrulama kodu", "12345");
    submit();
    expect(screen.getByText("Kod biçimi geçersiz.")).toBeInTheDocument();
    expect(onVerify).not.toHaveBeenCalled();
  });

  it("clears the error as soon as the code is edited", () => {
    setup();
    submit();
    type("Doğrulama kodu", "1");
    expect(screen.queryByText("Kodu yaz.")).not.toBeInTheDocument();
  });

  it("submits the trimmed authenticator code to onVerify", async () => {
    const { onVerify, onRecover } = setup();
    type("Doğrulama kodu", " 123456 ");
    submit();
    await waitFor(() => expect(onVerify).toHaveBeenCalledWith("123456"));
    expect(onRecover).not.toHaveBeenCalled();
  });

  it("switches to the recovery code with its own label, hint and empty field", () => {
    setup();
    type("Doğrulama kodu", "123456");
    fireEvent.click(screen.getByRole("button", { name: "Kurtarma kodu kullan" }));

    expect(screen.getByLabelText("Kurtarma kodu")).toHaveValue("");
    expect(screen.getByLabelText("Kurtarma kodu")).toHaveAttribute("maxlength", "24");
    expect(screen.getByText(/Her kod bir kez çalışır\./)).toBeInTheDocument();
    expect(screen.queryByText("Doğrulama uygulamandaki 6 haneli kodu yaz.")).not.toBeInTheDocument();
  });

  it("submits a recovery code to onRecover and not to onVerify", async () => {
    const { onVerify, onRecover } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Kurtarma kodu kullan" }));
    type("Kurtarma kodu", "abcd-efgh-ijkl-mnop-qrst");
    submit();
    await waitFor(() => expect(onRecover).toHaveBeenCalledWith("abcd-efgh-ijkl-mnop-qrst"));
    expect(onVerify).not.toHaveBeenCalled();
  });

  it("drops an old error when the code kind changes", () => {
    setup();
    submit();
    expect(screen.getByText("Kodu yaz.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Kurtarma kodu kullan" }));
    expect(screen.queryByText("Kodu yaz.")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Uygulama kodu kullan" }));
    expect(screen.getByLabelText("Doğrulama kodu")).toBeInTheDocument();
  });

  it("goes back through onBack", () => {
    const { onBack } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Geri dön" }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it("disables the button while pending and shows the server's problem", () => {
    setup({ pending: true, problem: "Kod hatalı ya da süresi dolmuş." });
    expect(screen.getByRole("button", { name: "Doğrula" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Kod hatalı ya da süresi dolmuş.");
  });
});
