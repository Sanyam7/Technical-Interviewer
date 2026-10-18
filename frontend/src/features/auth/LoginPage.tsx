import { useState, type SyntheticEvent } from 'react';
import { Link } from 'react-router';

import { ApiError } from '../../api/http';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { TextField } from '../../components/ui/Field';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { describeError } from '../../lib/errors';
import { AuthFrame } from './AuthFrame';
import { useAuth } from './authContext';

export function LoginPage() {
  usePageTitle('Sign in');
  const { signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await signIn(email.trim(), password);
    } catch (failure) {
      setError(failure);
      setSubmitting(false);
    }
  }

  const wrongCredentials = error instanceof ApiError && error.code === 'INVALID_CREDENTIALS';

  return (
    <AuthFrame
      title="Welcome back"
      description="Sign in to continue your practice."
      footer={
        <>
          New here?{' '}
          <Link to="/register" className="text-brand font-semibold underline underline-offset-4">
            Create an account
          </Link>
        </>
      }
    >
      <form onSubmit={(event) => void onSubmit(event)} className="space-y-5">
        {error !== null ? (
          <Alert
            tone="danger"
            title={wrongCredentials ? 'Sign-in failed' : describeError(error).title}
          >
            {wrongCredentials
              ? 'The email or the password is not right.'
              : describeError(error).message}
          </Alert>
        ) : null}
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          required
          value={email}
          onChange={(event) => {
            setEmail(event.target.value);
          }}
        />
        <TextField
          label="Password"
          type="password"
          name="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => {
            setPassword(event.target.value);
          }}
        />
        <Button type="submit" size="lg" className="w-full" loading={submitting}>
          Sign in
        </Button>
      </form>
    </AuthFrame>
  );
}
