'use client';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { FiBookOpen, FiCheckSquare, FiChevronLeft, FiClock, FiFile, FiInbox, FiMonitor, FiPlus, FiSettings, FiUsers, FiZap } from 'react-icons/fi';
import ChatList from './ChatList';
import { api, downloadPath, isLocal } from '../../lib/v2/api';
import { DICT, LangContext, detectLang, useT } from '../../lib/v2/i18n';
import { BotEditor, GroupCreator } from './BotEditor';
import Conversation from './Conversation';
import ComputerView from './ComputerView';
import InboxPanel from './InboxPanel';
import SettingsPanel from './SettingsPanel';
import SetupWizard from './SetupWizard';
import TasksPanel, { TaskDetail } from './TasksPanel';
import { MemoryPanel, RoutinesPanel, SkillsPanel } from './AutomationPanels';
import { Empty, Section, botLabel, cls, fmtTime, inputCls } from './ui';
import { useWorkspace } from './useWorkspace';

function FilesPanel({ ws, conversationId }) {
  const [rows, setRows] = useState([]);
  useEffect(() => { api(`/artifacts${conversationId ? `?conversation_id=${conversationId}` : ''}`).then(setRows); }, [conversationId, ws.tick]);
  const dl = (a) => downloadPath(`/artifacts/${a.id}/download`, a.name);
  return (
    <Section title="Files">
      {rows.length ? rows.map((a) => (
        <button key={a.id} onClick={() => dl(a)} className="w-full text-left py-2 px-1 hover:bg-white/5 rounded text-[15px] min-h-[44px]">
          📄 {a.name} <span className="text-[13px] text-zinc-500">v{a.version} · {(a.size / 1024).toFixed(1)} KB · {fmtTime(a.created_at)}{a.task_id ? ' · task' : ''}</span>
        </button>)) : <Empty />}
    </Section>
  );
}

function SearchPanel({ onOpenConversation, onOpenTask }) {
  const [q, setQ] = useState('');
  const [r, setR] = useState(null);
  useEffect(() => {
    if (q.length < 2) { setR(null); return undefined; }
    const h = setTimeout(() => api(`/search?q=${encodeURIComponent(q)}`).then(setR).catch(() => {}), 250);
    return () => clearTimeout(h);
  }, [q]);
  return (
    <Section title="Search">
      <input autoFocus className={inputCls} value={q} onChange={(e) => setQ(e.target.value)} placeholder="…" />
      {r && <div className="mt-2 space-y-2 text-[15px]">
        {r.bots.map((b) => <div key={b.id}>{botLabel(b)} @{b.handle}</div>)}
        {r.messages.map((m) => <button key={m.id} className="block text-left w-full hover:bg-white/5 rounded p-1" onClick={() => onOpenConversation(m.conversation_id)}>💬 {m.text}</button>)}
        {r.tasks.map((x) => <button key={x.id} className="block text-left w-full hover:bg-white/5 rounded p-1" onClick={() => onOpenTask(x.id)}>✅ {x.title} <span className="text-[13px] text-zinc-500">{x.status}</span></button>)}
        {r.memories.map((m) => <div key={m.id} className="text-zinc-400">🧠 {m.content}</div>)}
        {r.artifacts.map((a) => <div key={a.id}>📄 {a.name}</div>)}
      </div>}
    </Section>
  );
}

export default function Workspace() {
  const [lang, setLangState] = useState('en');
  const [authed, setAuthed] = useState(null);
  useEffect(() => { setLangState(detectLang()); }, []);
  const setLang = useCallback((l) => { setLangState(l); window.localStorage.setItem('opendots.lang', l); document.documentElement.lang = l; }, []);
  const t = useCallback((k) => DICT[lang][k] || DICT.en[k] || k, [lang]);
  const ctx = useMemo(() => ({ lang, t, setLang }), [lang, t, setLang]);

  // Deep link from a scanned pairing QR: opendots://pair?server=...&code=...
  useEffect(() => {
    const appPlugin = window.Capacitor?.Plugins?.App;
    if (!appPlugin) return undefined;
    const sub = appPlugin.addListener('appUrlOpen', ({ url }) => {
      try {
        const u = new URL(url);
        if (u.protocol === 'opendots:' && u.host === 'pair') {
          window.location.replace(`/bots/?server=${encodeURIComponent(u.searchParams.get('server') || '')}&code=${encodeURIComponent(u.searchParams.get('code') || '')}`);
        }
      } catch { /* ignore malformed links */ }
    });
    return () => { sub?.then?.((h) => h.remove()); };
  }, []);

  const check = useCallback(() => api('/bots').then((b) => setAuthed(b.length ? 'ok' : 'empty')).catch(() => setAuthed('no')), []);
  useEffect(() => { check(); const h = () => setAuthed('no'); window.addEventListener('opendots:auth-required', h); return () => window.removeEventListener('opendots:auth-required', h); }, [check]);

  return (
    <LangContext.Provider value={ctx}>
      {authed === null ? (
        <div className="h-[100dvh] bg-[#141414] flex flex-col items-center justify-center gap-4 lb-backdrop-in">
          <span className="lb-blob-busy inline-flex"><svg viewBox="0 0 100 64" width="96" height="62" aria-hidden="true"><rect x="2" y="2" width="96" height="60" rx="30" fill="#3b82f6" />
            <g transform="rotate(-12 54 20)"><rect className="lb-blob-eye" x="52" y="14" width="5" height="12" rx="2.5" fill="#1c1917" /></g>
            <g transform="rotate(12 66 20)"><rect className="lb-blob-eye" x="64" y="14" width="5" height="12" rx="2.5" fill="#1c1917" /></g></svg></span>
          <span className="text-zinc-500 text-[15px] tracking-wide">LowBot</span>
        </div>)
        : authed === 'ok' ? <Shell /> : <SetupWizard startAt={authed === 'empty' ? 2 : 0} onReady={() => { setAuthed('ok'); }} />}
    </LangContext.Provider>
  );
}

function useWide() {
  const [wide, setWide] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia('(min-width: 900px)');
    const on = () => setWide(mq.matches);
    on();
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  return wide;
}

// Full-screen page used on phones (and as a side sheet on desktop).
// Plays an exit animation before the parent removes the layer.
function useClosing(onClose, ms = 200) {
  const [closing, setClosing] = useState(false);
  const close = useCallback(() => { if (closing) return; setClosing(true); setTimeout(onClose, ms); }, [closing, onClose, ms]);
  return [closing, close];
}

function Page({ title, onBack, children, wide }) {
  const [closing, close] = useClosing(onBack);
  return (
    <div className={cls('fixed z-40 bg-[#141414] flex flex-col', wide ? 'inset-y-0 right-0 w-[520px] border-l border-white/10 shadow-2xl' : 'inset-0',
      closing ? 'lb-page-out' : (wide ? 'lb-side-in' : 'lb-page-in'))}>
      <header className="flex items-center gap-3 px-4 pb-3 shrink-0" style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}>
        <button aria-label="back" onClick={close} className="h-12 w-12 rounded-full bg-[#2a2a2a] border border-white/10 flex items-center justify-center text-2xl"><FiChevronLeft /></button>
        <h2 className="text-[19px] font-semibold truncate">{title}</h2>
      </header>
      <div className="lb-rise flex-1 overflow-y-auto px-4 pb-8 min-h-0" style={{ paddingBottom: 'max(2rem, env(safe-area-inset-bottom))' }}>{children}</div>
    </div>
  );
}

function Sheet({ onClose, children }) {
  const [closing, close] = useClosing(onClose, 180);
  return (
    <div className={cls('fixed inset-0 z-50 bg-black/60 flex items-end md:items-center justify-center', closing ? 'lb-backdrop-out' : 'lb-backdrop-in')} onClick={close}>
      <div className={cls('lb-stagger w-full md:w-[420px] rounded-t-[28px] md:rounded-[28px] bg-[#1f1f1f] p-3 pb-6', closing ? 'lb-sheet-out' : 'lb-sheet-in')} onClick={(e) => e.stopPropagation()}
        style={{ paddingBottom: 'max(1.5rem, env(safe-area-inset-bottom))' }}>
        <div className="mx-auto mb-3 h-1.5 w-10 rounded-full bg-white/20 md:hidden" />
        {children}
      </div>
    </div>
  );
}

function SheetItem({ icon, label, badge, onClick }) {
  return (
    <button onClick={onClick} className="lb-press w-full flex items-center gap-4 px-4 py-3.5 rounded-2xl hover:bg-white/5 active:bg-white/10 text-[16px] text-left">
      <span className="text-xl w-6 text-zinc-300 flex justify-center">{icon}</span>
      <span className="flex-1">{label}</span>
      {badge > 0 && <span className="min-w-[22px] h-[22px] px-1.5 rounded-full bg-rose-600 text-[12px] leading-[22px] text-center text-white">{badge}</span>}
    </button>
  );
}

function Shell() {
  const wide = useWide();
  const { t } = useT();
  const ws = useWorkspace(true);
  const [active, setActive] = useState(null);
  const [page, setPage] = useState(null); // {kind, data}
  const [sheet, setSheet] = useState(null); // 'menu' | 'new'
  const [side, setSide] = useState('tasks');
  const [skills, setSkills] = useState([]);
  const reloadSkills = useCallback(() => api('/skills').then(setSkills), []);
  useEffect(() => { reloadSkills(); }, [reloadSkills]);
  const conversation = ws.conversations.find((c) => c.id === active);
  const attention = ws.approvals.length + ws.elicitations.length;
  const inboxCount = attention + (ws.notifications.unread || 0);
  const activeCount = ws.tasks.filter((x) => !['completed', 'failed', 'cancelled'].includes(x.status)).length;

  const openConv = (id) => { setActive(id); setPage(null); setSheet(null); };
  const [share, setShare] = useState(null);

  // Android: text shared from another app, and taps on notifications.
  useEffect(() => {
    if (!isLocal()) return undefined;
    const take = () => { const x = window.LowBotNative.consumeShare?.(); if (x) setShare(x); };
    const open = (e) => { if (e.detail?.conversation_id) { setActive(null); setTimeout(() => openConv(e.detail.conversation_id), 0); } };
    take();
    window.addEventListener('lowbot:share', take);
    window.addEventListener('lowbot:open', open);
    return () => { window.removeEventListener('lowbot:share', take); window.removeEventListener('lowbot:open', open); };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const sendShareTo = async (bot, conv) => {
    window.__lowbotPendingShare = share;
    setShare(null);
    const id = conv ? conv.id : (await api(`/bots/${bot.id}/conversation`, { method: 'POST' })).id;
    setActive(null);
    await ws.reload();
    setTimeout(() => openConv(id), 0);
  };
  const openTask = (id) => setPage({ kind: 'task', data: id });
  const panels = {
    tasks: () => <TasksPanel ws={ws} />,
    inbox: () => <InboxPanel ws={ws} onOpenTask={openTask} />,
    computer: (botId) => <ComputerView ws={ws} botId={botId} />,
    files: () => <FilesPanel ws={ws} conversationId={active} />,
    routines: () => <RoutinesPanel ws={ws} />,
    memory: () => <MemoryPanel ws={ws} />,
    skills: () => <SkillsPanel ws={ws} skills={skills} reloadSkills={reloadSkills} />,
    settings: () => <SettingsPanel ws={ws} />,
    search: () => <SearchPanel onOpenConversation={openConv} onOpenTask={openTask} />,
  };
  const titles = { tasks: t('tasks'), inbox: t('inbox'), computer: t('computer'), files: t('files'), routines: t('routines'),
    memory: t('memory'), skills: t('skills'), settings: t('settings'), search: t('search') };

  const pageView = page && page.kind === 'bot' ? <BotEditor ws={ws} bot={page.data} onDone={() => setPage(null)} /> : page && (
    <Page wide={wide} onBack={() => setPage(null)} title={
      page.kind === 'task' ? t('tasks') : page.kind === 'bot' ? (page.data?.name || t('newBot')) : page.kind === 'group' ? t('newGroup')
        : page.kind === 'computer' ? `${t('computer')}${page.data ? ` · ${page.data.name}` : ''}` : titles[page.kind]}>
      {page.kind === 'task' && <TaskDetail taskId={page.data} ws={ws} />}
      {page.kind === 'group' && <GroupCreator ws={ws} onDone={(c) => { setPage(null); if (c) openConv(c.id); }} />}
      {page.kind === 'computer' && panels.computer(page.data?.id)}
      {panels[page.kind] && page.kind !== 'computer' && panels[page.kind]()}
    </Page>
  );

  const menu = sheet === 'menu' && (
    <Sheet onClose={() => setSheet(null)}>
      <SheetItem icon={<FiInbox />} label={`${t('approvals')} · ${t('notifications')}`} badge={inboxCount} onClick={() => { setSheet(null); setPage({ kind: 'inbox' }); }} />
      <SheetItem icon={<FiCheckSquare />} label={t('tasks')} badge={activeCount} onClick={() => { setSheet(null); setPage({ kind: 'tasks' }); }} />
      <SheetItem icon={<FiMonitor />} label={t('computer')} onClick={() => { setSheet(null); setPage({ kind: 'computer' }); }} />
      <SheetItem icon={<FiClock />} label={t('routines')} onClick={() => { setSheet(null); setPage({ kind: 'routines' }); }} />
      <SheetItem icon={<FiBookOpen />} label={t('memory')} onClick={() => { setSheet(null); setPage({ kind: 'memory' }); }} />
      <SheetItem icon={<FiZap />} label={t('skills')} onClick={() => { setSheet(null); setPage({ kind: 'skills' }); }} />
      <SheetItem icon={<FiFile />} label={t('files')} onClick={() => { setSheet(null); setPage({ kind: 'files' }); }} />
      <SheetItem icon={<FiSettings />} label={t('settings')} onClick={() => { setSheet(null); setPage({ kind: 'settings' }); }} />
      <div className={cls('px-4 pt-2 text-[12px]', ws.connection === 'live' ? 'text-emerald-400' : 'text-amber-400')}>● {t(`connection_${ws.connection}`)}</div>
    </Sheet>
  );
  const newSheet = sheet === 'new' && (
    <Sheet onClose={() => setSheet(null)}>
      <div className="px-4 pb-2 text-[13px] text-zinc-400">{t('newChoice')}</div>
      <SheetItem icon={<FiPlus />} label={t('newBot')} onClick={() => { setSheet(null); setPage({ kind: 'bot', data: null }); }} />
      <SheetItem icon={<FiUsers />} label={t('newGroup')} onClick={() => { setSheet(null); setPage({ kind: 'group' }); }} />
    </Sheet>
  );

  const shareSheet = share && (
    <Sheet onClose={() => setShare(null)}>
      <div className="px-4 pb-2 text-[13px] text-zinc-400 line-clamp-3">{t('send')}: {share}</div>
      {ws.conversations.filter((c) => c.kind === 'group').map((c) => (
        <SheetItem key={c.id} icon={<FiUsers />} label={c.title || c.bot_ids.map((id) => ws.bots.find((b) => b.id === id)?.name).join(', ')} onClick={() => sendShareTo(null, c)} />))}
      {ws.bots.filter((b) => !b.hidden).map((b) => <SheetItem key={b.id} icon={b.avatar || '🤖'} label={b.name} onClick={() => sendShareTo(b, null)} />)}
    </Sheet>
  );

  // Android Back button: close the top-most layer; false = let the app go to background.
  useEffect(() => {
    window.__lowbotBack = () => {
      if (share) { setShare(null); return true; }
      if (sheet) { setSheet(null); return true; }
      if (page) { setPage(null); return true; }
      if (active && !wide) { setActive(null); return true; }
      return false;
    };
    return () => { delete window.__lowbotBack; };
  }, [sheet, page, active, wide, share]);

  const list = <ChatList ws={ws} activeId={active} onOpen={openConv} onProfile={() => setSheet('menu')} onNew={() => setSheet('new')}
    attention={attention} compact={wide} />;
  const conv = conversation && <Conversation key={conversation.id} conversation={conversation} ws={ws} skills={skills}
    onBack={wide ? null : () => setActive(null)}
    onOpenBot={(bot) => setPage(bot && conversation.kind === 'private' ? { kind: 'bot', data: bot } : { kind: 'tasks' })}
    onOpenComputer={(bot) => setPage({ kind: 'computer', data: bot })} />;

  const sideTabs = [['tasks', t('tasks')], ['inbox', `${t('inbox')}${inboxCount ? ` ${inboxCount}` : ''}`], ['computer', t('computer')], ['files', t('files')]];
  return (
    <div className="h-[100dvh] bg-[#141414] text-zinc-100 overflow-hidden">
      {wide ? (
        <div className="flex h-full">
          <aside className="w-[340px] border-r border-white/[0.06] min-h-0">{list}</aside>
          <main className="flex-1 min-w-0 min-h-0">{conv || <div className="h-full flex items-center justify-center text-zinc-600">{ws.bots.length} {t('bots').toLowerCase()} · {activeCount} active</div>}</main>
          <aside className="w-[380px] border-l border-white/[0.06] flex flex-col min-h-0">
            <nav className="flex gap-1 p-3">{sideTabs.map(([k, label]) => (
              <button key={k} onClick={() => setSide(k)} className={cls('px-3 py-1.5 rounded-full text-[13px]', side === k ? 'bg-white text-black' : 'bg-[#2a2a2a] text-zinc-300')}>{label}</button>))}</nav>
            <div className="flex-1 overflow-y-auto px-3 pb-3 min-h-0">{panels[side]()}</div>
          </aside>
        </div>
      ) : (conv || list)}
      {pageView}
      {menu}
      {newSheet}
      {shareSheet}
    </div>
  );
}
