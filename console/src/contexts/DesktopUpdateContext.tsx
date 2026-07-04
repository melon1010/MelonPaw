import { createContext, useContext, type ReactNode } from "react";

export type UpdatePhase =
  | "idle"
  | "checking"
  | "downloading"
  | "installing"
  | "downloaded"
  | "failed";

interface UpdateError {
  stage?: string;
  kind?: string;
  message: string;
}

interface ContextValue {
  phase: UpdatePhase;
  isBackground: boolean;
  hasUpdate: boolean;
  supportsLaterInstall: boolean;
  version: string;
  body: string;
  downloaded: number;
  total: number | null;
  throughputBps: number;
  error: UpdateError | null;
  startInstall: () => Promise<void>;
  startBackgroundDownload: () => Promise<void>;
  installDownloaded: () => Promise<void>;
  retry: () => Promise<void>;
  dismissFailure: () => void;
}

const noopAsync = async () => {};
const noop = () => {};

const value: ContextValue = {
  phase: "idle",
  isBackground: false,
  hasUpdate: false,
  supportsLaterInstall: false,
  version: "",
  body: "",
  downloaded: 0,
  total: null,
  throughputBps: 0,
  error: null,
  startInstall: noopAsync,
  startBackgroundDownload: noopAsync,
  installDownloaded: noopAsync,
  retry: noopAsync,
  dismissFailure: noop,
};

const DesktopUpdateContext = createContext<ContextValue>(value);

export function DesktopUpdateProvider({ children }: { children: ReactNode }) {
  return (
    <DesktopUpdateContext.Provider value={value}>
      {children}
    </DesktopUpdateContext.Provider>
  );
}

export function useDesktopUpdate(): ContextValue {
  return useContext(DesktopUpdateContext);
}
