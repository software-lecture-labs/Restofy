import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import tr from "@/shared/lib/i18n/messages/tr.json";

/** Renders a component with the real Turkish messages, as the application does. */
export function renderWithIntl(ui: ReactElement) {
  return render(
    <NextIntlClientProvider locale="tr" messages={tr}>
      {ui}
    </NextIntlClientProvider>,
  );
}
