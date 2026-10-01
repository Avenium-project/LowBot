'use client';
// Home screen: one calm list of bots and groups, newest activity first.
import { useEffect, useMemo, useState } from 'react';
import { FiPlus, FiSearch, FiX } from 'react-icons/fi';
import { api } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { BotBlob, RoundButton, cls, shortTime } from './ui';

const BUSY = ['working', 'queued', 'retrying', 'waiting'];
const ATTN = ['needs_approval', 'needs_input', 'needs_resolution'];

export default function ChatList({ ws, activeId, onOpen, onProfile, onNew, attention, compact }) {
  const { t } = useT();
  const [q, setQ] = useState('');
  const [searching, setSearching] = useState(false);
  const [initial, setInitial] = useState('•');
  useEffect(() => { setInitial(((window.localStorage.getItem('opendots.name') || '').trim()[0] || '•').toUpperCase()); }, [ws.tick]);
  const rows = useMemo(() => {
    const byBot = Object.fromEntries(ws.conversations.filter((c) => c.kind === 'private').map((c) => [c.default_bot_id, c]));
    const out = ws.bots.filter((b) => !b.hidden).map((b) => ({ key: b.id, bot: b, conv: byBot[b.id], name: b.name }));
    for (const c of ws.conversations.filter((x) => x.kind === 'group')) {
      out.push({ key: c.id, conv: c, group: true,
        name: c.title || c.bot_ids.map((id) => ws.bots.find((b) => b.id === id)?.name).filter(Boolean).join(', ') });
    }
    const at = (r) => r.conv?.last_message?.created_at || r.conv?.updated_at || r.bot?.created_at || '';
    return out.filter((r) => !q || r.name.toLowerCase().includes(q.toLowerCase()))
      .sort((a, b) => (Number(b.bot?.pinned || 0) - Number(a.bot?.pinned || 0)) || at(b).localeCompare(at(a)));
  }, [ws.bots, ws.conversations, q]);

  const open = async (r) => {
    const conv = r.conv || await api(`/bots/${r.bot.id}/conversation`, { method: 'POST' });
    onOpen(conv.id);
    if (!r.conv) ws.reload(new Set(['conversations']));
  };

  const preview = (r) => {
    const status = r.bot?.status;
    if (ATTN.includes(status)) return { text: t(`status_${status}`), tone: 'text-amber-300' };
    if (BUSY.includes(status)) return { text: `${t(`status_${status}`)}…`, tone: 'text-emerald-300' };
    const m = r.conv?.last_message;
    if (!m) return { text: r.bot?.role_description || (t('typeMessage')), tone: 'text-zinc-500' };
    const who = m.author_type === 'user' ? `${t('you')}: ` : (r.group && m.author_id ? `${ws.bots.find((b) => b.id === m.author_id)?.name || ''}: ` : '');
    return { text: who + m.text.replace(/\s+/g, ' '), tone: 'text-zinc-400' };
  };

  return (
    <div className="flex flex-col h-full min-h-0 bg-[#141414]">
      <header className="flex items-center justify-between px-4 pb-3 shrink-0" style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}>
        <RoundButton label={t('menu')} onClick={onProfile} badge={attention}>
          <span className="h-12 w-12 rounded-full bg-[#8d6e63] flex items-center justify-center text-lg font-semibold ring-2 ring-[#2a2a2a]">{initial}</span>
        </RoundButton>
        {searching
          ? <div className="flex-1 mx-3 flex items-center rounded-full bg-[#2a2a2a] px-4 h-12">
              <FiSearch className="text-zinc-400 mr-2" />
              <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('search')}
                className="bg-transparent flex-1 outline-none text-[15px] text-zinc-100 min-w-0" />
              <button aria-label={t('cancel')} onClick={() => { setQ(''); setSearching(false); }} className="text-zinc-400"><FiX /></button>
            </div>
          : <div className="flex gap-3">
              <RoundButton label={t('search')} onClick={() => setSearching(true)}><FiSearch /></RoundButton>
              <RoundButton label={t('newBot')} onClick={onNew}><FiPlus /></RoundButton>
            </div>}
      </header>
      <div className="flex-1 overflow-y-auto min-h-0">
        {rows.map((r) => {
          const p = preview(r);
          const unread = r.conv?.unread > 0;
          return (
            <button key={r.key} onClick={() => open(r)}
              className={cls('w-full flex items-center gap-4 px-4 text-left transition active:bg-white/5', compact ? 'py-2.5' : 'py-3',
                activeId && r.conv?.id === activeId ? 'bg-white/[0.06]' : 'hover:bg-white/[0.03]')}>
              <BotBlob bot={r.bot} group={r.group} size={compact ? 46 : 56} busy={BUSY.includes(r.bot?.status)} />
              <span className="flex-1 min-w-0">
                <span className="flex items-baseline justify-between gap-2">
                  <span className="text-[17px] font-semibold text-zinc-50 truncate">{r.name}</span>
                  <span className="text-[13px] text-zinc-500 shrink-0">{shortTime(r.conv?.last_message?.created_at)}</span>
                </span>
                <span className="flex items-center justify-between gap-2 mt-0.5">
                  <span className={cls('text-[15px] truncate', p.tone)}>{p.text}</span>
                  {(unread || ATTN.includes(r.bot?.status)) && <span className={cls('h-2.5 w-2.5 rounded-full shrink-0', ATTN.includes(r.bot?.status) ? 'bg-amber-400' : 'bg-blue-500')} />}
                </span>
              </span>
            </button>
          );
        })}
        {!rows.length && <div className="text-center text-zinc-500 text-sm mt-16 px-8">{q ? t('empty') : t('emptyBots')}</div>}
      </div>
    </div>
  );
}
