import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';

import { login, logout, register } from '../../api/auth';
import { onSessionLost, refreshSession } from '../../api/session';
import { clearSavedDrafts } from '../../lib/draftStorage';
import { AuthContext, type AuthApi, type AuthState } from './authContext';

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error('The backend could not be reached.');
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);

  // A page load has no token: ask for one with the refresh cookie. Strict Mode runs this twice; the two calls share one request.
  useEffect(() => {
    let cancelled = false;
    refreshSession().then(
      (token) => {
        if (!cancelled) {
          setState(
            token === null
              ? { status: 'anonymous' }
              : { status: 'authenticated', user: token.user },
          );
        }
      },
      (error: unknown) => {
        if (!cancelled) {
          setState({ status: 'unavailable', error: asError(error) });
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  useEffect(() => {
    onSessionLost(() => {
      queryClient.clear();
      setState({ status: 'anonymous' });
    });
    return () => {
      onSessionLost(null);
    };
  }, [queryClient]);

  const signIn = useCallback(
    async (email: string, password: string) => {
      const token = await login({ email, password });
      queryClient.clear();
      setState({ status: 'authenticated', user: token.user });
    },
    [queryClient],
  );

  const signUp = useCallback(
    async (displayName: string, email: string, password: string) => {
      const token = await register({ displayName, email, password });
      queryClient.clear();
      setState({ status: 'authenticated', user: token.user });
    },
    [queryClient],
  );

  const signOut = useCallback(async () => {
    try {
      await logout();
    } catch {
      // The token is already dropped; the server revokes the session when the cookie expires at the latest.
    }
    queryClient.clear();
    clearSavedDrafts();
    setState({ status: 'anonymous' });
  }, [queryClient]);

  const retry = useCallback(() => {
    setState({ status: 'loading' });
    setAttempt((count) => count + 1);
  }, []);

  const value = useMemo<AuthApi>(
    () => ({ state, signIn, signUp, signOut, retry }),
    [state, signIn, signUp, signOut, retry],
  );

  return <AuthContext value={value}>{children}</AuthContext>;
}
