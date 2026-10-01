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

const TASK_LABELS = {
  pl: { completed: 'gotowe', failed: 'błąd', cancelled: 'zatrzymane', running: 'pracuje', queued: 'w kolejce',
    unknown_outcome: 'wymaga decyzji', waiting_approval: 'czeka na zgodę', waiting_input: 'czeka na odpowiedź',
    waiting_dependency: 'czeka na bota', retry_scheduled: 'ponawia', paused: 'wstrzymane', proposed: 'proponowane',
    awaiting_approval: 'czeka na zgodę', ready: 'gotowe do wykonania', executing: 'wykonuje', waiting: 'czeka',
    denied: 'odrzucone', unknown: 'niepewny wynik' },
};

export function TaskBadge({ status }) {
  const { lang } = useT();
  const label = TASK_LABELS[lang]?.[status] || String(status || '').replace(/_/g, ' ');
  const c = {
    completed: 'bg-emerald-900/60 text-emerald-300', failed: 'bg-rose-900/60 text-rose-300',
    cancelled: 'bg-zinc-800 text-zinc-400', running: 'bg-sky-900/60 text-sky-300', queued: 'bg-zinc-800 text-zinc-300',
    unknown_outcome: 'bg-rose-900/60 text-rose-200',
  }[status] || 'bg-amber-900/50 text-amber-200';
  return <span className={cls('rounded-full px-2 py-0.5 text-[11px] font-medium whitespace-nowrap', c)}>{label}</span>;
}

export function fmtTime(iso) {
  if (!iso) return '';
  try { return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' }); } catch { return iso; }
}

// Friendly blob avatar (no external assets). Colour is stable per bot id;
// an emoji avatar other than the default robot is shown inside the blob.
const BLOB_COLORS = ['#ff6a00', '#3b82f6', '#a855f7', '#10b981', '#f43f5e', '#eab308', '#06b6d4', '#ec4899'];

export function colorFor(id = '') {
  let h = 0;
  for (let i = 0; i < id.length; i += 1) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return BLOB_COLORS[h % BLOB_COLORS.length];
}

export function BotBlob({ bot, size = 48, group, busy }) {
  const color = group ? '#52525b' : colorFor(bot?.id);
  const emoji = !group && bot?.avatar && bot.avatar !== '🤖' ? bot.avatar : null;
  return (
    <span className="relative inline-flex shrink-0 items-center justify-center" style={{ width: size, height: size }}>
      <svg viewBox="0 0 100 64" width={size} height={size * 0.64} aria-hidden="true">
        <rect x="2" y="2" width="96" height="60" rx="30" fill={color} />
        {!emoji && !group && <>
          <rect x="52" y="14" width="5" height="12" rx="2.5" fill="#1c1917" transform="rotate(-12 54 20)" />
          <rect x="64" y="14" width="5" height="12" rx="2.5" fill="#1c1917" transform="rotate(12 66 20)" />
        </>}
      </svg>
      {(emoji || group) && <span className="absolute text-[0.9em]" style={{ fontSize: size * 0.32 }}>{group ? '👥' : emoji}</span>}
      {busy && <span className="absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full bg-emerald-400 ring-2 ring-[#141414] animate-pulse" />}
    </span>
  );
}

export function RoundButton({ children, onClick, label, badge }) {
  return (
    <button onClick={onClick} aria-label={label} title={label}
      className="relative h-12 w-12 rounded-full bg-[#2a2a2a] hover:bg-[#333] active:scale-95 transition flex items-center justify-center text-xl text-zinc-100">
      {children}
      {badge > 0 && <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-rose-600 text-[11px] leading-[18px] text-white">{badge}</span>}
    </button>
  );
}

export function shortTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}
