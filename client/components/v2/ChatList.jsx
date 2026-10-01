'use client';
// Home screen. Pinned chats as big characters on top, then your sections, then the rest.
// Long-press (or right-click) a chat: Mark as unread · Pin · New section · Hide · More.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FiChevronLeft, FiChevronRight, FiEye, FiEyeOff, FiFolderPlus, FiMessageSquare, FiMoreHorizontal, FiPlus, FiSearch, FiX } from 'react-icons/fi';
import { BsPin, BsPinAngle } from 'react-icons/bs';
import { api } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { BotBlob, RoundButton, cls, shortTime } from './ui';

const BUSY = ['working', 'queued', 'retrying', 'waiting'];
const ATTN = ['needs_approval', 'needs_input', 'needs_resolution'];
const STORE = 'lowbot.home';

// Group pin/hide and sections are a per-device arrangement of the list.
function loadPrefs() {
  try { return { sections: [], groupPinned: [], groupHidden: [], ...JSON.parse(window.localStorage.getItem(STORE) || '{}') }; }
  catch { return { sections: [], groupPinned: [], groupHidden: [] }; }
}
function savePrefs(p) { try { window.localStorage.setItem(STORE, JSON.stringify(p)); } catch { /* storage unavailable */ } }

function useLongPress(onLong) {
  const timer = useRef(null);
  const fired = useRef(false);
  const start = useRef(null);
  const clear = () => { clearTimeout(timer.current); timer.current = null; };
  return {
    onPointerDown: (e) => {
      fired.current = false;
      start.current = [e.clientX, e.clientY];
      const target = e.currentTarget;
      timer.current = setTimeout(() => { fired.current = true; navigator.vibrate?.(12); onLong(target); }, 450);
    },
    onPointerMove: (e) => { if (start.current && Math.hypot(e.clientX - start.current[0], e.clientY - start.current[1]) > 10) clear(); },
    onPointerUp: clear,
    onPointerCancel: clear,
    onContextMenu: (e) => { e.preventDefault(); clear(); fired.current = true; onLong(e.currentTarget); },
    wasLong: () => fired.current,
  };
}

function MenuItem({ icon, label, onClick, danger, chevron }) {
  return (
    <button onClick={onClick} className={cls('lb-press w-full flex items-center gap-4 px-5 py-3.5 text-left text-[17px] hover:bg-white/5', danger ? 'text-rose-400' : 'text-zinc-100')}>
      <span className={cls('text-[20px] w-6 flex justify-center', danger ? 'text-rose-400' : 'text-zinc-400')}>{icon}</span>
      <span className="flex-1">{label}</span>
      {chevron && <FiChevronRight className="text-zinc-500" />}
    </button>
  );
}

function ContextMenu({ at, onClose, children }) {
  const [closing, setClosing] = useState(false);
  const close = () => { setClosing(true); setTimeout(onClose, 150); };
  const top = Math.min(at.bottom + 8, (typeof window !== 'undefined' ? window.innerHeight : 800) - 380);
  return (
    <div className={cls('fixed inset-0 z-50', closing ? 'lb-backdrop-out' : 'lb-backdrop-in')} onClick={close} onContextMenu={(e) => { e.preventDefault(); close(); }}>
      <div className="absolute inset-0 bg-black/40" />
      <div onClick={(e) => e.stopPropagation()} style={{ top: Math.max(16, top), left: 16 }}
        className={cls('absolute w-[min(86vw,360px)] rounded-[26px] bg-[#2a2a2c] border border-white/10 shadow-2xl overflow-hidden py-2 lb-stagger', closing ? 'lb-backdrop-out' : 'lb-pop')}>
        {children(close)}
      </div>
    </div>
  );
}

function Row({ r, i, compact, activeId, menuKey, onLong, onOpen, preview }) {
  const lp = useLongPress((el) => onLong(r, el.getBoundingClientRect()));
  const p = preview(r);
  const unread = r.conv?.unread > 0;
  const attn = ATTN.includes(r.bot?.status);
  return (
    <button {...lp} onClick={() => { if (!lp.wasLong()) onOpen(r); }} style={{ '--i': i }}
      className={cls('lb-press w-full flex items-center gap-4 px-4 text-left transition active:bg-white/5', compact ? 'py-2.5' : 'py-3',
        menuKey === r.key ? 'bg-white/[0.08]' : activeId && r.conv?.id === activeId ? 'bg-white/[0.06]' : 'hover:bg-white/[0.03]')}>
      <BotBlob bot={r.bot} group={r.group} size={compact ? 46 : 56} busy={BUSY.includes(r.bot?.status)} attention={attn} />
      <span className="flex-1 min-w-0">
        <span className="flex items-baseline justify-between gap-2">
          <span className="text-[17px] font-semibold text-zinc-50 truncate">{r.name}</span>
          <span className="text-[13px] text-zinc-500 shrink-0">{shortTime(r.conv?.last_message?.created_at)}</span>
        </span>
        <span className="flex items-center justify-between gap-2 mt-0.5">
          <span className={cls('text-[15px] truncate', p.tone)}>{p.text}</span>
          {(unread || attn) && <span className={cls('lb-pop h-2.5 w-2.5 rounded-full shrink-0', attn ? 'bg-amber-400' : 'bg-blue-500')} />}
        </span>
      </span>
    </button>
  );
}

function Tile({ r, i, compact, menuKey, onLong, onOpen }) {
  const lp = useLongPress((el) => onLong(r, el.getBoundingClientRect()));
  const unread = r.conv?.unread > 0;
  const attn = ATTN.includes(r.bot?.status);
  return (
    <button {...lp} onClick={() => { if (!lp.wasLong()) onOpen(r); }} style={{ '--i': i }}
      className={cls('lb-press flex flex-col items-center gap-2 py-2 rounded-3xl', menuKey === r.key && 'bg-white/[0.06]')}>
      <BotBlob bot={r.bot} group={r.group} size={compact ? 76 : 100} busy={BUSY.includes(r.bot?.status)} attention={attn} />
      <span className="flex items-center gap-1.5 max-w-full px-1">
        <span className="text-[16px] text-zinc-300 truncate">{r.name}</span>
        {(unread || attn) && <span className={cls('lb-pop h-2.5 w-2.5 rounded-full shrink-0', attn ? 'bg-amber-400' : 'bg-blue-500')} />}
      </span>
    </button>
  );
}

export default function ChatList({ ws, activeId, onOpen, onProfile, onNew, attention, compact, onOpenBot }) {
  const { t } = useT();
  const [q, setQ] = useState('');
  const [searching, setSearching] = useState(false);
  const [initial, setInitial] = useState('•');
  const [prefs, setPrefsState] = useState({ sections: [], groupPinned: [], groupHidden: [] });
  const [menu, setMenu] = useState(null); // { row, at, more }
  const [showHidden, setShowHidden] = useState(false);
  const [naming, setNaming] = useState(null); // { row, name }
  useEffect(() => { setPrefsState(loadPrefs()); }, []);
  useEffect(() => { setInitial(((window.localStorage.getItem('opendots.name') || '').trim()[0] || '•').toUpperCase()); }, [ws.tick]);
  const setPrefs = useCallback((fn) => setPrefsState((p) => { const n = fn(p); savePrefs(n); return n; }), []);

  const rows = useMemo(() => {
    const byBot = Object.fromEntries(ws.conversations.filter((c) => c.kind === 'private').map((c) => [c.default_bot_id, c]));
    const out = ws.bots.map((b) => ({ key: b.id, bot: b, conv: byBot[b.id], name: b.name, pinned: !!b.pinned, hidden: !!b.hidden }));
    for (const c of ws.conversations.filter((x) => x.kind === 'group')) {
      out.push({ key: c.id, conv: c, group: true, pinned: prefs.groupPinned.includes(c.id), hidden: prefs.groupHidden.includes(c.id),
        name: c.title || c.bot_ids.map((id) => ws.bots.find((b) => b.id === id)?.name).filter(Boolean).join(', ') });
    }
    const at = (r) => r.conv?.last_message?.created_at || r.conv?.updated_at || r.bot?.created_at || '';
    return out.sort((a, b) => at(b).localeCompare(at(a)));
  }, [ws.bots, ws.conversations, prefs]);

  const visible = rows.filter((r) => !r.hidden && (!q || r.name.toLowerCase().includes(q.toLowerCase())));
  const hidden = rows.filter((r) => r.hidden);
  const pinned = q ? [] : visible.filter((r) => r.pinned);
  const sectionOf = (key) => prefs.sections.find((s) => s.keys.includes(key));
  const sections = q ? [] : prefs.sections.map((s) => ({ ...s, rows: visible.filter((r) => !r.pinned && s.keys.includes(r.key)) })).filter((s) => s.rows.length);
  const rest = visible.filter((r) => (q || !r.pinned) && (q || !sectionOf(r.key)));

  const open = async (r) => {
    const conv = r.conv || await api(`/bots/${r.bot.id}/conversation`, { method: 'POST' });
    onOpen(conv.id);
    if (!r.conv) ws.reload(new Set(['conversations']));
  };

  const reload = () => ws.reload(new Set(['bots', 'conversations']));
  const togglePin = (r) => (r.group
    ? setPrefs((p) => ({ ...p, groupPinned: r.pinned ? p.groupPinned.filter((k) => k !== r.key) : [...p.groupPinned, r.key] }))
    : api(`/bots/${r.bot.id}`, { method: 'PATCH', body: { pinned: !r.pinned } }).then(reload));
  const toggleHide = (r) => (r.group
    ? setPrefs((p) => ({ ...p, groupHidden: r.hidden ? p.groupHidden.filter((k) => k !== r.key) : [...p.groupHidden, r.key] }))
    : api(`/bots/${r.bot.id}`, { method: 'PATCH', body: { hidden: !r.hidden } }).then(reload));
  const toggleUnread = async (r) => {
    const conv = r.conv || await api(`/bots/${r.bot.id}/conversation`, { method: 'POST' });
    if (r.conv?.unread > 0) await api(`/conversations/${conv.id}/read`, { method: 'POST', body: { seq: r.conv.last_seq } });
    else await api(`/conversations/${conv.id}/unread`, { method: 'POST' });
    reload();
  };
  const moveTo = (r, sectionId) => setPrefs((p) => ({ ...p, sections: p.sections
    .map((s) => ({ ...s, keys: s.keys.filter((k) => k !== r.key).concat(s.id === sectionId ? [r.key] : []) }))
    .filter((s) => s.keys.length) }));
  const newSection = (r, name) => setPrefs((p) => ({ ...p, sections: [...p.sections.map((s) => ({ ...s, keys: s.keys.filter((k) => k !== r.key) })),
    { id: `s${Date.now()}`, name: name.trim(), keys: [r.key] }].filter((s) => s.keys.length) }));

  const preview = (r) => {
    const status = r.bot?.status;
    if (ATTN.includes(status)) return { text: t(`status_${status}`), tone: 'text-amber-400' };
    if (BUSY.includes(status)) return { text: `${t(`status_${status}`)}…`, tone: 'text-emerald-400' };
    const m = r.conv?.last_message;
    if (!m) return { text: r.bot?.role_description || t('typeMessage'), tone: 'text-zinc-500' };
    const who = m.author_type === 'user' ? `${t('you')}: ` : (r.group && m.author_id ? `${ws.bots.find((b) => b.id === m.author_id)?.name || ''}: ` : '');
    return { text: who + m.text.replace(/[*_`#>~]+/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/\s+/g, ' '), tone: 'text-zinc-400' };
  };

  const header = (
    <header className="flex items-center justify-between px-4 pb-3 shrink-0" style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}>
      {showHidden
        ? <RoundButton label="back" onClick={() => setShowHidden(false)}><FiChevronLeft /></RoundButton>
        : <RoundButton label={t('menu')} onClick={onProfile} badge={attention}>
            <span className="h-12 w-12 rounded-full bg-[#8d6e63] flex items-center justify-center text-lg font-semibold ring-2 ring-[#2a2a2a]">{initial}</span>
          </RoundButton>}
      {showHidden ? <span className="flex-1 mx-3 text-[19px] font-semibold">Hidden chats</span>
        : searching
          ? <div className="lb-rise flex-1 mx-3 flex items-center rounded-full bg-[#2a2a2a] px-4 h-12">
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
  );

  const m = menu?.row;
  return (
    <div className="flex flex-col h-full min-h-0 bg-[#141414]">
      {header}
      <div className="flex-1 overflow-y-auto min-h-0">
        {showHidden ? (
          <div key="hidden" className="lb-side-in lb-stagger">
            {hidden.map((r, i) => <Row key={r.key} r={r} i={i} compact={compact} menuKey={menu?.row.key} onLong={(row, at) => setMenu({ row, at })} onOpen={open} activeId={activeId} preview={preview} />)}
            {!hidden.length && <div className="text-center text-zinc-500 text-[15px] mt-16">No hidden chats</div>}
          </div>
        ) : <>
          {pinned.length > 0 && <div className="grid grid-cols-3 gap-x-2 gap-y-3 px-3 pt-2 pb-4 lb-stagger">{pinned.map((r, i) => <Tile key={r.key} r={r} i={i} compact={compact} menuKey={menu?.row.key} onLong={(row, at) => setMenu({ row, at })} onOpen={open} />)}</div>}
          {sections.map((s) => (
            <div key={s.id} className="mb-2">
              <div className="px-5 pt-3 pb-1 text-[14px] text-zinc-500">{s.name}</div>
              <div className="lb-stagger">{s.rows.map((r, i) => <Row key={r.key} r={r} i={i} compact={compact} menuKey={menu?.row.key} onLong={(row, at) => setMenu({ row, at })} onOpen={open} activeId={activeId} preview={preview} />)}</div>
            </div>))}
          {sections.length > 0 && rest.length > 0 && <div className="px-5 pt-3 pb-1 text-[14px] text-zinc-500">Chats</div>}
          <div className="lb-stagger">{rest.map((r, i) => <Row key={r.key} r={r} i={i} compact={compact} menuKey={menu?.row.key} onLong={(row, at) => setMenu({ row, at })} onOpen={open} activeId={activeId} preview={preview} />)}</div>
          {!q && hidden.length > 0 && (
            <button onClick={() => setShowHidden(true)} className="lb-press flex items-center gap-2 px-5 py-5 text-[17px] text-zinc-400">
              Hidden chats <span className="text-zinc-600">{hidden.length}</span> <FiChevronRight className="text-zinc-500" /></button>)}
          {!visible.length && !hidden.length && <div className="text-center text-zinc-500 text-sm mt-16 px-8">{q ? t('empty') : t('emptyBots')}</div>}
        </>}
      </div>

      {menu && (
        <ContextMenu at={menu.at} onClose={() => setMenu(null)}>
          {(close) => (menu.more ? <>
            <MenuItem icon={<FiChevronLeft />} label="Back" onClick={() => setMenu({ ...menu, more: false })} />
            {!m.group && <MenuItem icon={<FiMessageSquare />} label="Bot profile" onClick={() => { close(); onOpenBot?.(m.bot); }} />}
            {prefs.sections.filter((s) => !s.keys.includes(m.key)).map((s) => (
              <MenuItem key={s.id} icon={<FiFolderPlus />} label={`Move to ${s.name}`} onClick={() => { moveTo(m, s.id); close(); }} />))}
            {sectionOf(m.key) && <MenuItem icon={<FiX />} label={`Remove from ${sectionOf(m.key).name}`} onClick={() => { moveTo(m, null); close(); }} />}
            {!m.group && <MenuItem icon={<FiMoreHorizontal />} label={t('duplicate')} onClick={() => { close(); api(`/bots/${m.bot.id}/duplicate`, { method: 'POST' }).then(reload); }} />}
            {!m.group && <MenuItem danger icon={<FiX />} label={t('delete')} onClick={() => { close(); if (confirm(`Delete ${m.name}? Its routines and memory are removed too.`)) api(`/bots/${m.bot.id}`, { method: 'DELETE' }).then(reload); }} />}
          </> : <>
            <MenuItem icon={<FiMessageSquare />} label={m.conv?.unread > 0 ? 'Mark as read' : 'Mark as unread'} onClick={() => { close(); toggleUnread(m); }} />
            <MenuItem icon={m.pinned ? <BsPinAngle /> : <BsPin />} label={m.pinned ? 'Unpin' : 'Pin'} onClick={() => { close(); togglePin(m); }} />
            <MenuItem icon={<FiFolderPlus />} label="New section" onClick={() => { close(); setNaming({ row: m, name: '' }); }} />
            <MenuItem danger={!m.hidden} icon={m.hidden ? <FiEye /> : <FiEyeOff />} label={m.hidden ? 'Unhide' : 'Hide'} onClick={() => { close(); toggleHide(m); }} />
            <MenuItem icon={<FiMoreHorizontal />} label="More" chevron onClick={() => setMenu({ ...menu, more: true })} />
          </>)}
        </ContextMenu>
      )}

      {naming && (
        <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-6 lb-backdrop-in" onClick={() => setNaming(null)}>
          <form onClick={(e) => e.stopPropagation()} onSubmit={(e) => { e.preventDefault(); if (naming.name.trim()) { newSection(naming.row, naming.name); setNaming(null); } }}
            className="lb-pop w-full max-w-sm rounded-[26px] bg-[#2a2a2c] p-5 space-y-4">
            <div className="text-[18px] font-semibold">New section</div>
            <input autoFocus value={naming.name} onChange={(e) => setNaming({ ...naming, name: e.target.value })} placeholder="e.g. Work, Research"
              className="w-full rounded-2xl bg-[#1f1f1f] px-4 py-3 text-[16px] outline-none" />
            <div className="flex gap-2 justify-end">
              <button type="button" onClick={() => setNaming(null)} className="lb-press rounded-full bg-[#3a3a3c] px-5 py-2.5">{t('cancel')}</button>
              <button type="submit" disabled={!naming.name.trim()} className="lb-press rounded-full bg-white text-black px-5 py-2.5 font-medium disabled:opacity-40">{t('create')}</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
