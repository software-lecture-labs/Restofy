import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { NewPasswordForm, ResetCodeForm, ResetRequestForm } from "./password-reset-forms";

const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe("reset request form", () => {
  const submit = () => fireEvent.click(screen.getByRole("button", { name: "Kod gönder" }));

  it("shows what is missing and does not submit", () => {
    const onSubmit = vi.fn();
    renderWithIntl(<ResetRequestForm onSubmit={onSubmit} pending={false} />);
    submit();
    expect(screen.getByText("E-posta adresini yaz.")).toBeInTheDocument();
    expect(screen.getByText("Kodu yaz.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("rejects a malformed e-mail and a code that is not six digits", () => {
    renderWithIntl(<ResetRequestForm onSubmit={vi.fn()} pending={false} />);
    type("E-posta", "admin");
    type("Doğrulama uygulamasındaki kod", "123");
    submit();
    expect(screen.getByText("Geçerli bir e-posta adresi yaz.")).toBeInTheDocument();
    expect(screen.getByText("Kod 6 haneli olmalı.")).toBeInTheDocument();
  });

  it("clears a field's error when that field is edited", () => {
    renderWithIntl(<ResetRequestForm onSubmit={vi.fn()} pending={false} />);
    submit();
    type("E-posta", "a");
    expect(screen.queryByText("E-posta adresini yaz.")).not.toBeInTheDocument();
    expect(screen.getByText("Kodu yaz.")).toBeInTheDocument();
    type("Doğrulama uygulamasındaki kod", "1");
    expect(screen.queryByText("Kodu yaz.")).not.toBeInTheDocument();
  });

  it("submits the trimmed e-mail and code", async () => {
    const onSubmit = vi.fn();
    renderWithIntl(<ResetRequestForm onSubmit={onSubmit} pending={false} />);
    type("E-posta", "  admin@restofy.com ");
    type("Doğrulama uygulamasındaki kod", " 123456 ");
    submit();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith("admin@restofy.com", "123456"));
  });

  it("limits the input, keeps browser hints, and disables the button while pending", () => {
    renderWithIntl(<ResetRequestForm onSubmit={vi.fn()} pending problem="Çok fazla deneme yaptın. Biraz bekleyip tekrar dene." />);
    expect(screen.getByLabelText("E-posta")).toHaveAttribute("maxlength", "300");
    expect(screen.getByLabelText("Doğrulama uygulamasındaki kod")).toHaveAttribute("maxlength", "6");
    expect(screen.getByLabelText("Doğrulama uygulamasındaki kod")).toHaveAttribute("autocomplete", "one-time-code");
    expect(screen.getByRole("button", { name: "Kod gönder" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Çok fazla deneme");
  });
});

describe("reset code form", () => {
  const submit = () => fireEvent.click(screen.getByRole("button", { name: "Doğrula" }));

  it("asks for the code and rejects a malformed one", () => {
    const onSubmit = vi.fn();
    renderWithIntl(<ResetCodeForm onSubmit={onSubmit} pending={false} />);
    submit();
    expect(screen.getByText("Kodu yaz.")).toBeInTheDocument();
    type("E-postadaki kod", "12345");
    expect(screen.queryByText("Kodu yaz.")).not.toBeInTheDocument();
    submit();
    expect(screen.getByText("Kod 6 haneli olmalı.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("submits the trimmed code", async () => {
    const onSubmit = vi.fn();
    renderWithIntl(<ResetCodeForm onSubmit={onSubmit} pending={false} />);
    type("E-postadaki kod", " 123456 ");
    submit();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith("123456"));
  });

  it("disables the button while pending and shows the server's problem", () => {
    renderWithIntl(<ResetCodeForm onSubmit={vi.fn()} pending problem="Kod hatalı ya da süresi dolmuş." />);
    expect(screen.getByRole("button", { name: "Doğrula" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Kod hatalı");
  });
});

describe("new password form", () => {
  const submit = () => fireEvent.click(screen.getByRole("button", { name: "Parolayı kaydet" }));

  it("requires a password", () => {
    const onSubmit = vi.fn();
    renderWithIntl(<NewPasswordForm onSubmit={onSubmit} pending={false} />);
    submit();
    expect(screen.getByText("Yeni parolanı yaz.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("rejects a password under twelve characters", () => {
    renderWithIntl(<NewPasswordForm onSubmit={vi.fn()} pending={false} />);
    type("Yeni parola", "kisa");
    submit();
    expect(screen.getByText("Parola en az 12 karakter olmalı.")).toBeInTheDocument();
  });

  it("asks for the same password twice", () => {
    const onSubmit = vi.fn();
    renderWithIntl(<NewPasswordForm onSubmit={onSubmit} pending={false} />);
    type("Yeni parola", "bu-parola-yeterince-uzun");
    type("Yeni parola (tekrar)", "baska-bir-parola-123");
    submit();
    expect(screen.getByText("Parolalar aynı değil.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("clears a field's error when that field is edited", () => {
    renderWithIntl(<NewPasswordForm onSubmit={vi.fn()} pending={false} />);
    type("Yeni parola", "kisa");
    submit();
    type("Yeni parola", "kisa2");
    expect(screen.queryByText("Parola en az 12 karakter olmalı.")).not.toBeInTheDocument();

    type("Yeni parola", "bu-parola-yeterince-uzun");
    type("Yeni parola (tekrar)", "x");
    submit();
    expect(screen.getByText("Parolalar aynı değil.")).toBeInTheDocument();
    type("Yeni parola (tekrar)", "bu");
    expect(screen.queryByText("Parolalar aynı değil.")).not.toBeInTheDocument();
  });

  it("submits the password exactly as typed, without trimming it", async () => {
    const onSubmit = vi.fn();
    renderWithIntl(<NewPasswordForm onSubmit={onSubmit} pending={false} />);
    type("Yeni parola", " bu-parola-yeterince-uzun ");
    type("Yeni parola (tekrar)", " bu-parola-yeterince-uzun ");
    submit();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(" bu-parola-yeterince-uzun "));
  });

  it("hides the password, limits it to 72 characters and disables the button while pending", () => {
    renderWithIntl(<NewPasswordForm onSubmit={vi.fn()} pending problem="Parola kurallara uymuyor ya da eskisiyle aynı." />);
    expect(screen.getByLabelText("Yeni parola")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("Yeni parola")).toHaveAttribute("maxlength", "72");
    expect(screen.getByLabelText("Yeni parola")).toHaveAttribute("autocomplete", "new-password");
    expect(screen.getByRole("button", { name: "Parolayı kaydet" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Parola kurallara uymuyor");
  });
});
