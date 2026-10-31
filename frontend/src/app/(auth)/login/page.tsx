import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { LoginPage } from "@/features/auth/components/login-page";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Login");
  return { title: `${t("signIn")} | Restofy`, robots: { index: false, follow: false } };
}

export default function Login() {
  return <LoginPage />;
}
