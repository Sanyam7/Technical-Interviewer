import { useState, type SyntheticEvent } from 'react';
import { Link } from 'react-router';

import { ApiError } from '../../api/http';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { TextField } from '../../components/ui/Field';
import { usePageTitle } from '../../app/layout/usePageTitle';
import { describeError, formErrors } from '../../lib/errors';
import { AuthFrame } from './AuthFrame';
import { useAuth } from './authContext';

const MIN_PASSWORD_LENGTH = 10;

export function RegisterPage() {
  usePageTitle('Create your account');
  const { signUp } = useAuth();
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await signUp(displayName.trim(), email.trim(), password);
    } catch (failure) {
      setError(failure);
      setSubmitting(false);
    }
  }

  const { fields, unshown } = formErrors(error, ['displayName', 'email', 'password']);
  const emailTaken = error instanceof ApiError && error.code === 'EMAIL_TAKEN';
  const generalProblem =
    error !== null && (Object.keys(fields).length === 0 || unshown.length > 0) && !emailTaken;

  return (
    <AuthFrame
      title="Create your account"
      description="Create an account to set up your first practice interview."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="text-brand font-semibold underline underline-offset-4">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={(event) => void onSubmit(event)} className="space-y-5">
        {generalProblem ? (
          <Alert tone="danger" title={describeError(error).title}>
            {unshown.length > 0 ? unshown.join(' ') : describeError(error).message}
          </Alert>
        ) : null}
        <TextField
          label="Name"
          name="displayName"
          autoComplete="name"
          required
          maxLength={80}
          value={displayName}
          error={fields.displayName}
          onChange={(event) => {
            setDisplayName(event.target.value);
          }}
        />
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          required
          value={email}
          error={
            fields.email ??
            (emailTaken ? 'This email is already registered. Sign in instead?' : undefined)
          }
          onChange={(event) => {
            setEmail(event.target.value);
          }}
        />
        <TextField
          label="Password"
          type="password"
          name="password"
          autoComplete="new-password"
          required
          minLength={MIN_PASSWORD_LENGTH}
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          value={password}
          error={fields.password}
          onChange={(event) => {
            setPassword(event.target.value);
          }}
        />
        <Button type="submit" size="lg" className="w-full" loading={submitting}>
          Create account
        </Button>
      </form>
    </AuthFrame>
  );
}
