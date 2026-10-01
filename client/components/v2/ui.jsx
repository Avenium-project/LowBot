'use client';
// Small shared UI primitives for the v2 workspace.
import { useT } from '../../lib/v2/i18n';

export function cls(...xs) { return xs.filter(Boolean).join(' '); }

export function Button({ children, onClick, kind = 'default', small, disabled, type = 'button', title, className }) {
  const base = 'lb-press inline-flex items-center justify-center gap-1 rounded-lg font-medium transition disabled:opacity-40 disabled:cursor-not-allowed';
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
  const { lang } = useT();
  const label = String(status || '').replace(/_/g, ' ');
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

// Bot "character": a coloured shape with two eyes (Grok-style). Stored in bot.avatar as
// "shape:<name>:<#hex>"; an emoji avatar other than the default robot is still supported.
const BLOB_COLORS = ['#ff6a00', '#3b82f6', '#a855f7', '#10b981', '#f43f5e', '#eab308', '#06b6d4', '#ec4899'];
export const AVATAR_COLORS = ['#ffffff', '#8d6e4f', '#ef2b3c', '#ff6a00', '#ff9f0a', '#22c55e', '#14b8a6', '#1e88ff', '#9b5cf6', '#ff2d95', '#7a7a7a'];
// Paths in a 100x100 box; eyes sit in the upper right of each shape.
export const SHAPES = {
  circle: { d: 'M50 6a44 44 0 1 1 0 88a44 44 0 1 1 0-88z', eyes: [58, 32] },
  blob: { d: 'M52 10c26 0 42 14 42 38c0 26-20 42-46 42C22 90 6 74 6 50C6 26 24 10 52 10z', eyes: [58, 32] },
  square: { d: 'M30 8h40c14 0 22 8 22 22v40c0 14-8 22-22 22H30C16 92 8 84 8 70V30C8 16 16 8 30 8z', eyes: [58, 30] },
  pill: { d: 'M30 22h40a28 28 0 0 1 0 56H30a28 28 0 0 1 0-56z', eyes: [60, 38] },
  triangle: { d: 'M41 14c4.5-7.5 13.5-7.5 18 0l34 58c4.5 7.8-1 16-10 16H17c-9 0-14.5-8.2-10-16z', eyes: [52, 44] },
  hexagon: { d: 'M50 6l38 22v44L50 94L12 72V28z', eyes: [56, 34] },
  cloud: { d: 'M30 82c-14 0-24-10-24-22c0-11 8-20 19-21c2-15 13-25 27-25c13 0 24 9 27 21c10 1 17 10 17 21c0 14-10 26-26 26z', eyes: [58, 40] },
  drop: { d: 'M50 6c14 20 36 38 36 58c0 18-16 30-36 30S14 82 14 64C14 44 36 26 50 6z', eyes: [56, 50] },
};

export function parseAvatar(avatar) {
  const m = /^shape:([a-z]+):(#[0-9a-fA-F]{6})$/.exec(avatar || '');
  return m && SHAPES[m[1]] ? { shape: m[1], color: m[2] } : null;
}

export function colorFor(id = '') {
  let h = 0;
  for (let i = 0; i < id.length; i += 1) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return BLOB_COLORS[h % BLOB_COLORS.length];
}

export function ShapeIcon({ shape, color, size = 48, eyes = true, blinkDelay = 0 }) {
  const s = SHAPES[shape] || SHAPES.pill;
  const [ex, ey] = s.eyes;
  const eye = color.toLowerCase() === '#ffffff' ? '#3f3f46' : '#1c1917';
  return (
    <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true">
      <path d={s.d} fill={color} />
      {eyes && <>
        <g transform={`rotate(-10 ${ex} ${ey})`}><rect className="lb-blob-eye" style={{ animationDelay: `${blinkDelay}s` }} x={ex - 2.8} y={ey - 6.5} width="5.6" height="13" rx="2.8" fill={eye} /></g>
        <g transform={`rotate(10 ${ex + 12} ${ey})`}><rect className="lb-blob-eye" style={{ animationDelay: `${blinkDelay}s` }} x={ex + 9.2} y={ey - 6.5} width="5.6" height="13" rx="2.8" fill={eye} /></g>
      </>}
    </svg>
  );
}

export function BotBlob({ bot, size = 48, group, busy }) {
  const parsed = !group && parseAvatar(bot?.avatar);
  const color = parsed ? parsed.color : group ? '#52525b' : colorFor(bot?.id);
  const emoji = !group && !parsed && bot?.avatar && bot.avatar !== '🤖' ? bot.avatar : null;
  const delay = (colorFor(bot?.id || '').charCodeAt(2) % 5) * 0.7;
  return (
    <span className={cls('relative inline-flex shrink-0 items-center justify-center', busy && 'lb-blob-busy')} style={{ width: size, height: size }}>
      {parsed ? <ShapeIcon shape={parsed.shape} color={color} size={size} blinkDelay={delay} /> : (
        <svg viewBox="0 0 100 64" width={size} height={size * 0.64} aria-hidden="true">
          <rect x="2" y="2" width="96" height="60" rx="30" fill={color} />
          {!emoji && !group && <>
            <g transform="rotate(-12 54 20)"><rect className="lb-blob-eye" style={{ animationDelay: `${delay}s` }} x="52" y="14" width="5" height="12" rx="2.5" fill="#1c1917" /></g>
            <g transform="rotate(12 66 20)"><rect className="lb-blob-eye" style={{ animationDelay: `${delay}s` }} x="64" y="14" width="5" height="12" rx="2.5" fill="#1c1917" /></g>
          </>}
        </svg>)}
      {(emoji || group) && <span className="absolute text-[0.9em]" style={{ fontSize: size * 0.32 }}>{group ? '👥' : emoji}</span>}
      {busy && <span className="lb-pop absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full bg-emerald-400 ring-2 ring-[#141414] animate-pulse" />}
    </span>
  );
}

export function RoundButton({ children, onClick, label, badge }) {
  return (
    <button onClick={onClick} aria-label={label} title={label}
      className="relative h-12 w-12 rounded-full bg-[#2a2a2a] hover:bg-[#333] active:scale-95 transition flex items-center justify-center text-xl text-zinc-100">
      {children}
      {badge > 0 && <span key={badge} className="lb-pop absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-rose-600 text-[11px] leading-[18px] text-white">{badge}</span>}
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
