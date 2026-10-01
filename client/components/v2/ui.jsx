'use client';
// Small shared UI primitives for the v2 workspace.
import { useT } from '../../lib/v2/i18n';

export function cls(...xs) { return xs.filter(Boolean).join(' '); }

export function Button({ children, onClick, kind = 'default', small, disabled, type = 'button', title, className }) {
  const base = 'inline-flex items-center justify-center gap-1 rounded-lg font-medium transition disabled:opacity-40 disabled:cursor-not-allowed';
  const size = small ? 'px-2 py-1 text-xs min-h-[32px]' : 'px-3 py-2 text-sm min-h-[40px]';
  const kinds = {
    default: 'bg-zinc-800 hover:bg-zinc-700 text-zinc-100 border border-white/10',
    primary: 'bg-blue-600 hover:bg-blue-500 text-white',
    danger: 'bg-rose-700 hover:bg-rose-600 text-white',
    ghost: 'hover:bg-white/5 text-zinc-300',
    success: 'bg-emerald-700 hover:bg-emerald-600 text-white',
  };
  return <button type={type} title={title} disabled={disabled} onClick={onClick} className={cls(base, size, kinds[kind], className)}>{children}</button>;
}

export function Field({ label, children, hint }) {
  return (
    <label className="block text-sm">
      <span className="block text-zinc-400 mb-1">{label}</span>
      {children}
      {hint && <span className="block text-xs text-zinc-500 mt-1">{hint}</span>}
    </label>
  );
}

export const inputCls = 'w-full rounded-lg bg-zinc-900 border border-white/10 px-3 py-2 text-sm text-zinc-100 focus:outline-none focus:border-blue-500';

export function StatusDot({ status }) {
  const { t } = useT();
  const color = {
    working: 'bg-emerald-400 animate-pulse', queued: 'bg-sky-400', needs_approval: 'bg-amber-400',
    needs_input: 'bg-amber-400', needs_resolution: 'bg-rose-500', waiting: 'bg-violet-400', retrying: 'bg-orange-400',
    paused: 'bg-zinc-500', idle: 'bg-zinc-600',
  }[status] || 'bg-zinc-600';
  return (
    <span className="inline-flex items-center gap-1 text-xs text-zinc-400" aria-label={t(`status_${status}`)}>
      <span className={cls('inline-block h-2 w-2 rounded-full', color)} />
      {t(`status_${status}`)}
    </span>
  );
}

export function Card({ children, className }) {
  return <div className={cls('rounded-xl border border-white/10 bg-zinc-900/70 p-3', className)}>{children}</div>;
}

export function Empty({ children }) {
  const { t } = useT();
  return <div className="text-sm text-zinc-500 p-4 text-center">{children || t('empty')}</div>;
}

export function Section({ title, actions, children }) {
  return (
    <section className="mb-5">
      <div className="flex items-center justify-between mb-2 gap-2">
        <h3 className="text-sm font-semibold text-zinc-200">{title}</h3>
        <div className="flex gap-2 flex-wrap justify-end">{actions}</div>
      </div>
      {children}
    </section>
  );
}

export function TaskBadge({ status }) {
  const c = {
    completed: 'bg-emerald-900/60 text-emerald-300', failed: 'bg-rose-900/60 text-rose-300',
    cancelled: 'bg-zinc-800 text-zinc-400', running: 'bg-sky-900/60 text-sky-300', queued: 'bg-zinc-800 text-zinc-300',
    unknown_outcome: 'bg-rose-900/60 text-rose-200',
  }[status] || 'bg-amber-900/50 text-amber-200';
  return <span className={cls('rounded px-1.5 py-0.5 text-[11px] font-medium', c)}>{status}</span>;
}

export function fmtTime(iso) {
  if (!iso) return '';
  try { return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' }); } catch { return iso; }
}
