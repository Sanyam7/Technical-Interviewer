import { createContext, useContext } from 'react';

import type { User } from '../../api/types';

export type AuthState =
  | { status: 'loading' }
  /** The backend could not be reached while looking for a session; the user may try again. */
  | { status: 'unavailable'; error: Error }
  | { status: 'anonymous' }
  | { status: 'authenticated'; user: User };

export interface AuthApi {
  state: AuthState;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (displayName: string, email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** Looks for a session again after the backend was unreachable. */
  retry: () => void;
}

export const AuthContext = createContext<AuthApi | null>(null);

export function useAuth(): AuthApi {
  const auth = useContext(AuthContext);
  if (auth === null) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return auth;
}

/** The signed-in user; only for components rendered below <RequireAuth>. */
export function useUser(): User {
  const { state } = useAuth();
  if (state.status !== 'authenticated') {
    throw new Error('useUser needs a signed-in user; render this below <RequireAuth>');
  }
  return state.user;
}
