import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, clearSession, loadSession, persistSession, setUnauthorizedHandler } from "../api/client";
import { ApiError, type Session } from "../api/types";

interface AuthContextValue {
  session: Session | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession<Session>());

  const logout = () => {
    clearSession();
    setSession(null);
  };

  useEffect(() => {
    setUnauthorizedHandler(logout);
    return () => setUnauthorizedHandler(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      logout,
      async login(username: string, password: string) {
        try {
          const response = await api.login(username, password);
          const next: Session = {
            token: response.accessToken,
            username: response.username,
            role: response.role,
            participantId: response.participantId ?? null,
          };
          persistSession(next);
          setSession(next);
        } catch (error) {
          if (error instanceof ApiError) {
            throw error;
          }
          throw new Error("Unable to reach the switch. Is the backend running on port 9090?");
        }
      },
    }),
    [session],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** Hook for the current session. Lives next to the provider on purpose. */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return context;
}
