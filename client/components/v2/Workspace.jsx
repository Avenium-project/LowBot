'use client';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { FiCheckSquare, FiInbox, FiMessageSquare, FiMonitor, FiMoreHorizontal, FiPlus, FiSettings, FiUsers } from 'react-icons/fi';
import { api, fetchBlobUrl } from '../../lib/v2/api';
import { DICT, LangContext, detectLang, useT } from '../../lib/v2/i18n';
import { BotEditor, GroupCreator } from './BotEditor';
import Conversation from './Conversation';
import ComputerView from './ComputerView';
import InboxPanel from './InboxPanel';
import SettingsPanel from './SettingsPanel';
import SetupWizard from './SetupWizard';
import TasksPanel, { TaskDetail } from './TasksPanel';
import { MemoryPanel, RoutinesPanel, SkillsPanel } from './AutomationPanels';
import { Button, Empty, Section, StatusDot, cls, fmtTime, inputCls } from './ui';
import { useWorkspace } from './useWorkspace';

function FilesPanel({ ws, conversationId }) {
  const [rows, setRows] = useState([]);
  useEffect(() => { api(`/artifacts${conversationId ? `?conversation_id=${conversationId}` : ''}`).then(setRows); }, [conversationId, ws.tick]);
  const dl = async (a) => { const u = await fetchBlobUrl(`/artifacts/${a.id}/download`); const l = document.createElement('a'); l.href = u; l.download = a.name; l.click(); };
  return (
    <Section title="Files">
      {rows.length ? rows.map((a) => (
        <button key={a.id} onClick={() => dl(a)} className="w-full text-left py-2 px-1 hover:bg-white/5 rounded text-sm min-h-[44px]">
          📄 {a.name} <span className="text-xs text-zinc-500">v{a.version} · {(a.size / 1024).toFixed(1)} KB · {fmtTime(a.created_at)}{a.task_id ? ' · task' : ''}</span>
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
      {r && <div className="mt-2 space-y-2 text-sm">
        {r.bots.map((b) => <div key={b.id}>{b.avatar} {b.name} @{b.handle}</div>)}
        {r.messages.map((m) => <button key={m.id} className="block text-left w-full hover:bg-white/5 rounded p-1" onClick={() => onOpenConversation(m.conversation_id)}>💬 {m.text}</button>)}
        {r.tasks.map((x) => <button key={x.id} className="block text-left w-full hover:bg-white/5 rounded p-1" onClick={() => onOpenTask(x.id)}>✅ {x.title} <span className="text-xs text-zinc-500">{x.status}</span></button>)}
        {r.memories.map((m) => <div key={m.id} className="text-zinc-400">🧠 {m.content}</div>)}
        {r.artifacts.map((a) => <div key={a.id}>📄 {a.name}</div>)}
      </div>}
    </Section>
  );
}

function Sidebar({ ws, t, activeId, onOpen, onNewBot, onNewGroup, onEditBot }) {
  const [filter, setFilter] = useState('');
  const [showHidden, setShowHidden] = useState(false);
  const convByBot = useMemo(() => Object.fromEntries(ws.conversations.filter((c) => c.kind === 'private').map((c) => [c.default_bot_id, c])), [ws.conversations]);
  const groups = ws.conversations.filter((c) => c.kind === 'group');
  const bots = ws.bots.filter((b) => (showHidden || !b.hidden) && (!filter || `${b.name} ${b.handle}`.toLowerCase().includes(filter.toLowerCase())));
  const openBot = async (b) => {
    const c = convByBot[b.id] || await api(`/bots/${b.id}/conversation`, { method: 'POST' });
    onOpen(c.id);
    ws.reload(new Set(['conversations']));
  };
  return (
    <div className="flex flex-col h-full min-h-0">
      <div className="p-2 flex gap-2 shrink-0">
        <input className={inputCls} placeholder={t('search')} value={filter} onChange={(e) => setFilter(e.target.value)} />
        <Button small kind="primary" title={t('newBot')} onClick={onNewBot}><FiPlus /></Button>
        <Button small title={t('newGroup')} onClick={onNewGroup}><FiUsers /></Button>
      </div>
      <div className="flex-1 overflow-y-auto min-h-0 px-1">
        {groups.length > 0 && <div className="px-2 pt-2 text-[11px] uppercase text-zinc-500">{t('groups')}</div>}
        {groups.map((c) => (
          <button key={c.id} onClick={() => onOpen(c.id)} className={cls('w-full text-left px-2 py-2 rounded-lg flex items-center gap-2 min-h-[48px]', activeId === c.id ? 'bg-white/10' : 'hover:bg-white/5')}>
            <span className="h-9 w-9 rounded-full bg-zinc-800 flex items-center justify-center"><FiUsers /></span>
            <span className="flex-1 min-w-0"><span className="block truncate text-sm">{c.title || c.bot_ids.map((id) => ws.bots.find((b) => b.id === id)?.name).join(', ')}</span>
              <span className="block text-[11px] text-zinc-500">{c.bot_ids.length} bots</span></span>
            {c.unread > 0 && <span className="text-[10px] bg-blue-600 rounded-full px-1.5">{c.unread}</span>}
          </button>
        ))}
        <div className="px-2 pt-2 text-[11px] uppercase text-zinc-500 flex justify-between"><span>{t('bots')} ({ws.bots.length})</span>
          <button className="normal-case" onClick={() => setShowHidden(!showHidden)}>{showHidden ? t('hide') : t('unhide')}…</button></div>
        {bots.map((b) => {
          const c = convByBot[b.id];
          return (
            <div key={b.id} className={cls('flex items-center rounded-lg', c && activeId === c.id ? 'bg-white/10' : 'hover:bg-white/5')}>
              <button onClick={() => openBot(b)} className="flex-1 min-w-0 text-left px-2 py-2 flex items-center gap-2 min-h-[48px]">
                <span className="h-9 w-9 rounded-full bg-zinc-800 flex items-center justify-center text-lg">{b.avatar}</span>
                <span className="flex-1 min-w-0"><span className="block truncate text-sm">{b.pinned && '📌 '}{b.name}{b.hidden && ' 👁‍🗨'}</span><StatusDot status={b.status} /></span>
                {c?.unread > 0 && <span className="text-[10px] bg-blue-600 rounded-full px-1.5">{c.unread}</span>}
              </button>
              <button aria-label={t('edit')} className="px-2 text-zinc-500 min-h-[44px]" onClick={() => onEditBot(b)}><FiSettings /></button>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default function Workspace() {
  const [lang, setLangState] = useState('pl');
  const [authed, setAuthed] = useState(null);
  useEffect(() => { setLangState(detectLang()); }, []);
  const setLang = useCallback((l) => { setLangState(l); window.localStorage.setItem('opendots.lang', l); document.documentElement.lang = l; }, []);
  const t = useCallback((k) => DICT[lang][k] || DICT.en[k] || k, [lang]);
  const ctx = useMemo(() => ({ lang, t, setLang }), [lang, t, setLang]);

  const check = useCallback(() => api('/bots').then((b) => setAuthed(b.length ? 'ok' : 'empty')).catch(() => setAuthed('no')), []);
  useEffect(() => { check(); const h = () => setAuthed('no'); window.addEventListener('opendots:auth-required', h); return () => window.removeEventListener('opendots:auth-required', h); }, [check]);

  return (
    <LangContext.Provider value={ctx}>
      {authed === null ? <div className="h-[100dvh] bg-zinc-950" />
        : authed === 'ok' ? <Shell /> : <SetupWizard startAt={authed === 'empty' ? 2 : 0} onReady={() => { setAuthed('ok'); }} />}
    </LangContext.Provider>
  );
}

function useWide() {
  const [wide, setWide] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia('(min-width: 768px)');
    const on = () => setWide(mq.matches);
    on();
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  return wide;
}

function Shell() {
  const wide = useWide();
  const { t: T } = useT();
  const ws = useWorkspace(true);
  const [active, setActive] = useState(null); // conversation id
  const [tab, setTab] = useState('chats'); // mobile tab
  const [side, setSide] = useState('tasks'); // desktop right panel
  const [overlay, setOverlay] = useState(null); // {kind, data}
  const [skills, setSkills] = useState([]);
  const reloadSkills = useCallback(() => api('/skills').then(setSkills), []);
  useEffect(() => { reloadSkills(); }, [reloadSkills]);
  const conversation = ws.conversations.find((c) => c.id === active);
  const inboxCount = ws.approvals.length + (ws.notifications.unread || 0) + ws.elicitations.length;

  const openTask = (id) => setOverlay({ kind: 'task', data: id });
  const openConv = (id) => { setActive(id); setTab('chats'); setOverlay(null); };
  const panels = {
    tasks: <TasksPanel ws={ws} />,
    inbox: <InboxPanel ws={ws} onOpenTask={openTask} />,
    computer: <ComputerView ws={ws} />,
    files: <FilesPanel ws={ws} conversationId={active} />,
    routines: <RoutinesPanel ws={ws} />,
    memory: <MemoryPanel ws={ws} />,
    skills: <SkillsPanel ws={ws} skills={skills} reloadSkills={reloadSkills} />,
    settings: <SettingsPanel ws={ws} />,
    search: <SearchPanel onOpenConversation={openConv} onOpenTask={openTask} />,
  };
  const overlayView = overlay && (
    <div className="fixed inset-0 z-40 bg-black/60 flex justify-end" onClick={() => setOverlay(null)}>
      <div className="w-full md:w-[520px] h-full bg-zinc-950 border-l border-white/10 overflow-y-auto p-4" onClick={(e) => e.stopPropagation()} style={{ paddingTop: 'max(1rem, env(safe-area-inset-top))' }}>
        {overlay.kind === 'task' && <TaskDetail taskId={overlay.data} ws={ws} onClose={() => setOverlay(null)} />}
        {overlay.kind === 'bot' && <BotEditor ws={ws} bot={overlay.data} onDone={() => setOverlay(null)} />}
        {overlay.kind === 'group' && <GroupCreator ws={ws} onDone={(c) => { setOverlay(null); if (c) openConv(c.id); }} />}
      </div>
    </div>
  );
  const conn = <span className={cls('text-[11px]', ws.connection === 'live' ? 'text-emerald-400' : 'text-amber-400')}>● {T(`connection_${ws.connection}`) || ws.connection}</span>;
  const sidebar = <Sidebar ws={ws} t={T} activeId={active} onOpen={openConv} onNewBot={() => setOverlay({ kind: 'bot', data: null })}
    onNewGroup={() => setOverlay({ kind: 'group' })} onEditBot={(b) => setOverlay({ kind: 'bot', data: b })} />;
  const convView = conversation ? <Conversation key={conversation.id} conversation={conversation} ws={ws} skills={skills} onBack={() => setActive(null)} />
    : <div className="h-full flex items-center justify-center text-zinc-500 text-sm p-6 text-center">Open Dots — {ws.bots.length} {T('bots').toLowerCase()} · {ws.tasks.filter((x) => !['completed', 'failed', 'cancelled'].includes(x.status)).length} active</div>;

  const sideTabs = [['tasks', T('tasks')], ['inbox', `${T('inbox')}${inboxCount ? ` (${inboxCount})` : ''}`], ['computer', T('computer')], ['files', T('files')],
    ['routines', T('routines')], ['memory', T('memory')], ['skills', T('skills')], ['search', T('search')], ['settings', T('settings')]];

  return (
    <div className="h-[100dvh] bg-zinc-950 text-zinc-100 flex flex-col select-text overflow-hidden">
      {/* Desktop / tablet */}
      {wide && <div className="flex flex-1 min-h-0">
        <aside className="w-72 lg:w-80 border-r border-white/10 flex flex-col min-h-0">
          <div className="px-3 py-2 flex justify-between items-center border-b border-white/10"><b>Open Dots</b>{conn}</div>
          {sidebar}
        </aside>
        <main className="flex-1 min-w-0 min-h-0">{convView}</main>
        <aside className="w-[380px] xl:w-[440px] border-l border-white/10 flex flex-col min-h-0">
          <nav className="flex flex-wrap gap-1 p-2 border-b border-white/10">{sideTabs.map(([k, label]) => (
            <button key={k} onClick={() => setSide(k)} className={cls('px-2 py-1 rounded text-xs', side === k ? 'bg-white/10 text-white' : 'text-zinc-400 hover:text-zinc-200')}>{label}</button>))}</nav>
          <div className="flex-1 overflow-y-auto p-3 min-h-0">{panels[side]}</div>
        </aside>
      </div>}
      {/* Phone: its own navigation, not a shrunken desktop */}
      {!wide && <div className="flex-1 min-h-0 flex flex-col">
        {tab === 'chats' && active ? <div className="flex-1 min-h-0">{convView}</div> : (
          <>
            <div className="px-3 py-2 flex justify-between items-center border-b border-white/10 shrink-0" style={{ paddingTop: 'max(0.5rem, env(safe-area-inset-top))' }}>
              <b>{tab === 'chats' ? T('chats') : tab === 'more' ? T('more') : T(tab)}</b>{conn}</div>
            <div className="flex-1 min-h-0 overflow-y-auto">
              {tab === 'chats' && sidebar}
              {tab !== 'chats' && tab !== 'more' && <div className="p-3">{panels[tab]}</div>}
              {tab === 'more' && <div className="p-3 grid grid-cols-2 gap-2">{['routines', 'memory', 'skills', 'files', 'search', 'settings'].map((k) => (
                <Button key={k} onClick={() => setTab(k)}>{T(k)}</Button>))}</div>}
            </div>
          </>
        )}
        {!(tab === 'chats' && active) && (
          <nav className="grid grid-cols-5 border-t border-white/10 shrink-0 bg-zinc-950" style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}>
            {[['chats', FiMessageSquare, T('chats')], ['tasks', FiCheckSquare, T('tasks')], ['inbox', FiInbox, T('inbox')], ['computer', FiMonitor, T('computer')], ['more', FiMoreHorizontal, T('more')]].map(([k, Icon, label]) => (
              <button key={k} onClick={() => setTab(k)} aria-label={label} className={cls('flex flex-col items-center justify-center py-2 min-h-[56px] text-[11px] relative', tab === k ? 'text-sky-300' : 'text-zinc-400')}>
                <Icon className="text-lg" />{label}
                {k === 'inbox' && inboxCount > 0 && <span className="absolute top-1 right-[25%] text-[10px] bg-rose-600 text-white rounded-full px-1.5">{inboxCount}</span>}
              </button>))}
          </nav>
        )}
      </div>}
      {overlayView}
    </div>
  );
}
