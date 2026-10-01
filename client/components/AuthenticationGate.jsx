'use client';

import { useEffect, useState } from 'react';
import { AuthenticationError, ensureSession, login, logout } from '../lib/api';
import Dashboard from './Dashboard';

export default function AuthenticationGate() {
  const [phase, setPhase] = useState('loading');
  const [token, setToken] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const checkSession = async () => {
    setPhase('loading');
    setError('');
    try {
      await ensureSession();
      setPhase('authenticated');
    } catch (failure) {
      setPhase(failure instanceof AuthenticationError ? 'login' : 'unavailable');
      if (!(failure instanceof AuthenticationError)) setError('Cannot reach the API. Start the server and retry.');
    }
  };

  useEffect(() => {
    checkSession();
    const expired = () => { setToken(''); setError(''); setPhase('login'); };
    window.addEventListener('open-dots:authentication-required', expired);
    return () => window.removeEventListener('open-dots:authentication-required', expired);
  }, []);

  const signIn = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    const credential = token;
    setToken('');
    try {
      await login(credential);
      setPhase('authenticated');
    } catch (failure) {
      setError(failure.message || 'Sign-in failed.');
    } finally {
      setBusy(false);
    }
  };

  const signOut = async () => {
    try { await logout(); }
    catch (failure) { setError(failure.message); setPhase('unavailable'); }
  };

  if (phase === 'authenticated') return <Dashboard onLogout={signOut} />;
  return (
    <main className="min-h-screen bg-[#09090b] text-zinc-100 flex items-center justify-center p-6">
      <section className="w-full max-w-md rounded-2xl border border-zinc-800 bg-zinc-900 p-6 space-y-5">
        <h1 className="text-xl font-semibold">Sign in to Open Dots</h1>
        {phase === 'loading' ? <p role="status">Checking session…</p> : phase === 'unavailable' ? <>
          <p role="alert" className="text-sm text-red-300">{error}</p>
          <button onClick={checkSession} className="rounded-lg bg-violet-600 px-4 py-2 text-sm">Retry connection</button>
        </> : <form onSubmit={signIn} className="space-y-4">
          <p className="text-sm text-zinc-400">Enter the owner token configured on your server. For a local installation, read the .auth-token file in your data directory (normally ~/.open-dots).</p>
          <label htmlFor="owner-token" className="block text-sm">Owner token</label>
          <input id="owner-token" type="password" autoComplete="off" required maxLength={4096} value={token} onChange={(event) => setToken(event.target.value)} disabled={busy} className="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 focus:outline-violet-400" />
          <p className="text-xs text-zinc-500">This is the Open Dots login token. Your model provider key is configured after signing in.</p>
          {error && <p role="alert" className="text-sm text-red-300">{error}</p>}
          <button disabled={busy} type="submit" className="w-full rounded-lg bg-violet-600 px-4 py-2 text-sm disabled:opacity-50">{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>}
      </section>
    </main>
  );
}
