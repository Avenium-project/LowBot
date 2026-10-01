"use client";
// Bot profile, modelled on Grok Bot's bot settings: big character, name + title card,
// tabs (Info · Links · Media · Files), character shape/colour, model & provider,
// instructions, routines, notifications, share as template, and a ⋯ menu.
import { useCallback, useEffect, useMemo, useState } from 'react';
import { FiChevronLeft, FiChevronRight, FiFileText, FiMoreHorizontal, FiPlus, FiShare, FiCpu } from 'react-icons/fi';
import { api, downloadPath, isLocal, saveBlob } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Toggle, AVATAR_COLORS, BotBlob, Button, Field, SHAPES, Section, ShapeIcon, botLabel, cls, colorFor, inputCls, parseAvatar, shortTime } from './ui';

const card = 'rounded-[22px] bg-[#1f1f1f]';

function Row({ icon, label, onClick, right }) {
  return (
    <button type="button" onClick={onClick} className={cls(card, 'lb-press w-full flex items-center gap-4 px-5 py-4 text-left text-[17px]')}>
      <span className="text-xl text-zinc-300">{icon}</span><span className="flex-1">{label}</span>{right || <FiChevronRight className="text-zinc-500" />}
    </button>
  );
}

function Caption({ children }) { return <div className="px-6 pt-2 pb-1 text-[14px] text-zinc-500">{children}</div>; }
function Label({ children }) { return <div className="px-6 pt-5 pb-2 text-[14px] text-zinc-500">{children}</div>; }

// --------------------------------------------------------------- sub screens
function InstructionsScreen({ f, set, onBack, save, pl }) {
  return (
    <div className="lb-side-in space-y-3">
      <button onClick={onBack} className="flex items-center gap-1 text-sky-400 text-[15px]"><FiChevronLeft /> {'Back'}</button>
      <div className={cls(card, 'p-4 space-y-3')}>
        <Field label={'Role (one sentence)'}><input className={inputCls} value={f.role_description} onChange={set('role_description')} /></Field>
        <Field label={'Instructions'} hint={'How the bot should work: goals, style, what to avoid, when to ask you.'}>
          <textarea className={`${inputCls} h-64 text-[15px] leading-relaxed`} value={f.instructions} onChange={set('instructions')} /></Field>
        <Button kind="primary" onClick={save}>{'Save'}</Button>
      </div>
    </div>
  );
}

function RoutineForm({ botId, onDone, pl }) {
  const [r, setR] = useState({ name: '', schedule: 'every weekday at 8:00 AM', prompt: '' });
  const [preview, setPreview] = useState(null);
  const [err, setErr] = useState('');
  useEffect(() => {
    const h = setTimeout(() => api('/routines/parse', { method: 'POST', body: { text: r.schedule } })
      .then((x) => { setPreview(x.next_runs_local); setErr(''); }).catch((e) => { setPreview(null); setErr(e.message); }), 350);
    return () => clearTimeout(h);
  }, [r.schedule]);
  const save = async () => {
    try { await api('/routines', { method: 'POST', body: { bot_id: botId, ...r, name: r.name || r.prompt.slice(0, 40) || 'Routine' } }); onDone(); }
    catch (e) { setErr(e.message); }
  };
  return (
    <div className="lb-rise px-5 py-4 space-y-2 border-t border-white/5">
      <input className={inputCls} placeholder={'Name'} value={r.name} onChange={(e) => setR({ ...r, name: e.target.value })} />
      <input className={inputCls} placeholder={'When, e.g. every day at 9:00'} value={r.schedule} onChange={(e) => setR({ ...r, schedule: e.target.value })} />
      {preview && <div className="text-xs text-zinc-500">{'Next'}: {preview.slice(0, 3).join(' · ')}</div>}
      <textarea className={`${inputCls} h-20`} placeholder={'What should it do?'} value={r.prompt} onChange={(e) => setR({ ...r, prompt: e.target.value })} />
      {err && <div className="text-xs text-rose-300">{err}</div>}
      <div className="flex gap-2"><Button kind="primary" disabled={!r.prompt || !preview} onClick={save}>{'Add'}</Button><Button onClick={onDone}>{'Cancel'}</Button></div>
    </div>
  );
}

function Routines({ bot, pl, tick }) {
  const [rows, setRows] = useState([]);
  const [adding, setAdding] = useState(false);
  const load = useCallback(() => api(`/routines?bot_id=${bot.id}`).then(setRows).catch(() => {}), [bot.id]);
  useEffect(() => { load(); }, [load, tick]);
  return (
    <div className={cls(card, 'overflow-hidden')}>
      {rows.length ? rows.map((r) => (
        <div key={r.id} className="flex items-center gap-3 px-5 py-3.5 border-b border-white/5">
          <div className="flex-1 min-w-0"><div className="text-[16px] truncate">{r.name}</div>
            <div className="text-[13px] text-zinc-500 truncate">{r.enabled ? (r.preview?.[0] || r.schedule?.source) : ('paused')}</div></div>
          <Toggle on={r.enabled} label={r.name} onChange={(on) => api(`/routines/${r.id}`, { method: 'PATCH', body: { enabled: on } }).then(load)} />
        </div>)) : <div className="px-5 py-4 text-[16px] text-zinc-500 border-b border-white/5 pl-14">{'No routines yet'}</div>}
      {adding ? <RoutineForm botId={bot.id} pl={pl} onDone={() => { setAdding(false); load(); }} />
        : <button onClick={() => setAdding(true)} className="lb-press w-full flex items-center gap-4 px-5 py-4 text-sky-400 text-[17px]"><FiPlus className="text-xl" /> {'Add routine'}</button>}
    </div>
  );
}

function ModelPicker({ f, setF, providers, pl, onCommit }) {
  const profile = providers.find((p) => p.id === f.provider_profile_id) || providers.find((p) => p.is_default);
  const models = profile ? Array.from(new Set([...(profile.models || []), ...(profile.last_test?.models || []), profile.default_model].filter(Boolean))) : [];
  return (
    <div className={cls(card, 'p-4 space-y-3')}>
      <Field label={'Provider'}>
        <select className={inputCls} value={f.provider_profile_id} onChange={(e) => { const v = { ...f, provider_profile_id: e.target.value, model: '' }; setF(v); onCommit(v); }}>
          <option value="">{'Default'}{providers.find((p) => p.is_default) ? ` (${providers.find((p) => p.is_default).name})` : ''}</option>
          {providers.map((p) => <option key={p.id} value={p.id}>{p.name}{p.is_mock ? ' [mock]' : ''}</option>)}
        </select></Field>
      <Field label={'Model'} hint={models.length ? null : ('The list appears after Test connection in Settings → Models, or type an id.')}>
        {models.length ? (
          <select className={inputCls} value={models.includes(f.model) ? f.model : (f.model ? '__custom' : '')}
            onChange={(e) => { if (e.target.value === '__custom') return; const v = { ...f, model: e.target.value }; setF(v); onCommit(v); }}>
            <option value="">{'Provider default'}{profile?.default_model ? ` (${profile.default_model})` : ''}</option>
            {models.map((m) => <option key={m} value={m}>{m}</option>)}
            {f.model && !models.includes(f.model) && <option value="__custom">{f.model}</option>}
          </select>
        ) : <input className={inputCls} value={f.model} placeholder={profile?.default_model || 'model id'} onChange={(e) => setF({ ...f, model: e.target.value })} onBlur={() => onCommit(f)} />}
      </Field>
      {profile && <div className="text-xs text-zinc-500">{'Tools'}: {String(profile.capabilities?.tools ?? '?')} · {'vision'}: {String(profile.capabilities?.vision ?? '?')}{profile.is_mock ? ' · mock' : ''}</div>}
    </div>
  );
}

function Advanced({ f, set, ws, bot, pl, onSave }) {
  const { t } = useT();
  return (
    <details className={cls(card, 'p-4')}>
      <summary className="cursor-pointer text-[15px] text-zinc-300">{'Advanced'}</summary>
      <div className="space-y-2 mt-3">
        <Field label={t('tools')}><input className={inputCls} value={f.tools} onChange={set('tools')} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label={t('orgRole')}><select className={inputCls} value={f.org_role} onChange={set('org_role')}><option value="">—</option>{['ceo', 'head', 'manager', 'worker'].map((r) => <option key={r} value={r}>{r}</option>)}</select></Field>
          <Field label={t('reportsTo')}><select className={inputCls} value={f.reports_to} onChange={set('reports_to')}><option value="">—</option>{ws.bots.filter((b) => b.id !== bot?.id).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></Field>
          <Field label={t('computerMode')}><select className={inputCls} value={f.computer_mode} onChange={set('computer_mode')}><option value="shared">{t('shared')}</option><option value="isolated">{t('isolated')}</option></select></Field>
          <Field label={t('budget')}><input className={inputCls} type="number" step="0.01" value={f.budget} onChange={set('budget')} /></Field>
        </div>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.can_create_bots} onChange={set('can_create_bots')} /> {'can create bots'}</label>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.team_memory_access} onChange={set('team_memory_access')} /> {'team knowledge access'}</label>
        {onSave && <Button kind="primary" onClick={onSave}><FiCpu /> {'Save'}</Button>}
      </div>
    </details>
  );
}

// ------------------------------------------------------------- memory files
function FileScreen({ title, hint, value, onSave, onBack, extra }) {
  const [v, setV] = useState(value || '');
  const [err, setErr] = useState('');
  useEffect(() => { setV(value || ''); }, [value]);
  return (
    <div className="lb-side-in space-y-3">
      <button onClick={onBack} className="flex items-center gap-1 text-sky-400 text-[15px]"><FiChevronLeft /> Back</button>
      <div className={cls(card, 'p-4 space-y-3')}>
        <div className="font-mono text-[15px] text-zinc-200">{title}</div>
        {hint && <div className="text-[13px] text-zinc-500">{hint}</div>}
        <textarea className={`${inputCls} h-80 font-mono text-[13px] leading-relaxed`} value={v} onChange={(e) => setV(e.target.value)} />
        {err && <div className="text-xs text-rose-300">{err}</div>}
        <div className="flex gap-2 flex-wrap">
          <Button kind="primary" onClick={() => onSave(v).then(onBack).catch((e) => setErr(e.message))}>Save</Button>
          {extra}
        </div>
      </div>
    </div>
  );
}

function MemoryFiles({ bot, tick, open }) {
  const [m, setM] = useState(null);
  const [adding, setAdding] = useState(false);
  const [n, setN] = useState({ title: '', content: '' });
  const load = useCallback(() => api(`/bots/${bot.id}/mind`).then(setM).catch(() => {}), [bot.id]);
  useEffect(() => { load(); }, [load, tick]);
  if (!m) return null;
  const firstLine = (s) => (s || '').replace(/<!--.*?-->/g, '').trim().split('\n').find((x) => x.trim()) || '';
  return (
    <>
      <div className={cls(card, 'overflow-hidden')}>
        <button onClick={() => open({ kind: 'soul', value: m.soul, reload: load })} className="lb-press w-full flex items-center gap-4 px-5 py-4 text-left border-b border-white/5">
          <span className="text-xl">🫀</span><span className="flex-1 min-w-0"><span className="block text-[17px]">soul.md</span>
            <span className="block text-[13px] text-zinc-500 truncate">Purpose and behaviour · {firstLine(m.soul) || 'empty'}</span></span><FiChevronRight className="text-zinc-500" /></button>
        <button onClick={() => open({ kind: 'agents', value: m.agents, reload: load })} className="lb-press w-full flex items-center gap-4 px-5 py-4 text-left">
          <span className="text-xl">🔁</span><span className="flex-1 min-w-0"><span className="block text-[17px]">agents.md</span>
            <span className="block text-[13px] text-zinc-500 truncate">Handoff for the next session · {firstLine(m.agents) || 'no handoff yet'}</span></span><FiChevronRight className="text-zinc-500" /></button>
      </div>
      <Caption>The handoff is never compacted: when a conversation gets long, the bot clears agents.md, writes the exact goal and next steps, and continues from it with a fresh context.</Caption>

      <Label>Small memories</Label>
      <div className={cls(card, 'overflow-hidden lb-stagger')}>
        {m.memories.length ? m.memories.map((x) => (
          <div key={x.name} className="flex items-start gap-3 px-5 py-3 border-b border-white/5">
            <div className="flex-1 min-w-0"><div className="text-[15px]">{x.title}</div><div className="text-[13px] text-zinc-500 line-clamp-2">{x.content}</div></div>
            <button aria-label={`forget ${x.title}`} onClick={() => api(`/bots/${bot.id}/mind/memories/${encodeURIComponent(x.name)}`, { method: 'DELETE' }).then(setM)} className="text-zinc-500 hover:text-rose-300 px-2">✕</button>
          </div>)) : <div className="px-5 py-4 text-zinc-500 border-b border-white/5">No memories yet — the bot adds them as it learns.</div>}
        {adding ? (
          <div className="lb-rise px-5 py-4 space-y-2">
            <input className={inputCls} placeholder="Title" value={n.title} onChange={(e) => setN({ ...n, title: e.target.value })} />
            <textarea className={`${inputCls} h-20`} placeholder="What to remember" value={n.content} onChange={(e) => setN({ ...n, content: e.target.value })} />
            <div className="flex gap-2"><Button kind="primary" disabled={!n.title || !n.content}
              onClick={() => api(`/bots/${bot.id}/mind/memories`, { method: 'POST', body: n }).then((x) => { setM(x); setAdding(false); setN({ title: '', content: '' }); })}>Add</Button>
              <Button onClick={() => setAdding(false)}>Cancel</Button></div>
          </div>
        ) : <button onClick={() => setAdding(true)} className="lb-press w-full flex items-center gap-4 px-5 py-4 text-sky-400 text-[17px]"><FiPlus className="text-xl" /> Add memory</button>}
      </div>
      <Caption>{'Stored as memories/*.md in the bot\'s folder.'}</Caption>

      <Label>Projects</Label>
      <div className={cls(card, 'overflow-hidden')}>
        {m.projects.length ? m.projects.map((p) => (
          <button key={p.name} onClick={() => open({ kind: 'project', name: p.name, value: p.agents_md, reload: load })} className="lb-press w-full flex items-center gap-4 px-5 py-3.5 text-left border-b border-white/5">
            <span className="text-xl">📁</span><span className="flex-1 min-w-0"><span className="block text-[16px]">{p.name}/AGENTS.md</span>
              <span className="block text-[13px] text-zinc-500 truncate">{firstLine(p.agents_md) || 'no rules yet'}</span></span><FiChevronRight className="text-zinc-500" /></button>))
          : <div className="px-5 py-4 text-zinc-500">No projects yet. Ask the bot to work on a project, or bind a chat to one.</div>}
      </div>
      <Caption>Project AGENTS.md holds only how an agent should behave while working on that project.</Caption>
    </>
  );
}

// ---------------------------------------------------------------- tabs content
const URL_RE = /https?:\/\/[^\s)<>\]"']+/g;

function MediaTabs({ tab, conversationId, pl, tick }) {
  const [arts, setArts] = useState([]);
  const [msgs, setMsgs] = useState([]);
  useEffect(() => {
    if (!conversationId) return;
    api(`/artifacts?conversation_id=${conversationId}`).then(setArts).catch(() => {});
    if (tab === 'links') api(`/conversations/${conversationId}/messages?limit=500`).then(setMsgs).catch(() => {});
  }, [conversationId, tab, tick]);
  const links = useMemo(() => {
    const seen = new Map();
    msgs.forEach((m) => (m.text.match(URL_RE) || []).forEach((u) => { if (!seen.has(u)) seen.set(u, m.created_at); }));
    return Array.from(seen.entries()).reverse();
  }, [msgs]);
  const images = arts.filter((a) => a.mime.startsWith('image/') || a.mime.startsWith('video/') || a.mime.startsWith('audio/'));
  const files = arts.filter((a) => !images.includes(a));
  const empty = <div className="text-center text-zinc-500 py-12">{'Nothing here yet'}</div>;
  if (tab === 'links') return links.length ? <div className={cls(card, 'lb-stagger overflow-hidden')}>{links.map(([u, at]) => (
    <a key={u} href={u} onClick={(e) => { if (window.LowBotNative?.openExternal?.(u)) e.preventDefault(); }} target="_blank" rel="noopener noreferrer"
      className="block px-5 py-3 border-b border-white/5 text-sky-400 text-[15px] truncate">{u}<div className="text-[12px] text-zinc-500">{shortTime(at)}</div></a>))}</div> : empty;
  const list = tab === 'media' ? images : files;
  if (!list.length) return empty;
  return (
    <div className={cls('lb-stagger', tab === 'media' ? 'grid grid-cols-3 gap-2' : cls(card, 'overflow-hidden'))}>
      {list.map((a) => tab === 'media'
        ? <button key={a.id} onClick={() => downloadPath(`/artifacts/${a.id}/download`, a.name)} className="lb-press aspect-square rounded-2xl bg-[#1f1f1f] flex flex-col items-center justify-center text-xs text-zinc-400 p-2"><span className="text-3xl">🖼️</span><span className="truncate w-full text-center mt-1">{a.name}</span></button>
        : <button key={a.id} onClick={() => downloadPath(`/artifacts/${a.id}/download`, a.name)} className="lb-press w-full text-left px-5 py-3 border-b border-white/5"><div className="text-[15px] truncate">📄 {a.name}</div><div className="text-[12px] text-zinc-500">v{a.version} · {(a.size / 1024).toFixed(1)} KB · {shortTime(a.created_at)}</div></button>)}
    </div>
  );
}

// ------------------------------------------------------------------- profile
const EMPTY = { name: '', label: '', avatar: '', role_description: '', instructions: '', provider_profile_id: '', model: '',
  tools: 'workspace.*, web.fetch, http.post, memory.*, user.ask, secret.request, task.*, bot.message, artifact.share, browser.*, routine.create',
  org_role: '', reports_to: '', computer_mode: 'shared', can_create_bots: false, team_memory_access: false, budget: '', notify: true };

export function BotEditor({ ws, bot: initial, onDone }) {
  const { t, lang } = useT();
  const pl = lang === 'pl';
  const local = isLocal();
  const [bot, setBot] = useState(initial || null);
  const [f, setF] = useState(EMPTY);
  const [providers, setProviders] = useState([]);
  const [tab, setTab] = useState('info');
  const [screen, setScreen] = useState(null); // 'instructions'
  const [menu, setMenu] = useState(false);
  const [err, setErr] = useState('');
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    api('/providers').then((d) => setProviders(d.profiles.map((p) => ({ ...p, is_default: p.id === d.default_profile_id })))).catch(() => {});
  }, []);
  useEffect(() => {
    const b = bot && (ws.bots.find((x) => x.id === bot.id) || bot);
    if (b) setF({ ...EMPTY, ...b, label: b.label || '', tools: (b.tools || []).join(', '), provider_profile_id: b.provider_profile_id || '',
      model: b.model || '', org_role: b.org_role || '', reports_to: b.reports_to || '', budget: b.budget?.max_cost_per_day ?? '', notify: b.notify !== false });
    else setF({ ...EMPTY, name: 'New bot', avatar: `shape:${Object.keys(SHAPES)[Math.floor(Math.random() * 8)]}:${AVATAR_COLORS[2 + Math.floor(Math.random() * 8)]}` });
  }, [bot?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  const body = (v) => {
    const out = { name: v.name, avatar: v.avatar || '🤖', role_description: v.role_description, instructions: v.instructions,
      provider_profile_id: v.provider_profile_id || null, model: v.model || null,
      tools: v.tools.split(',').map((x) => x.trim()).filter(Boolean), org_role: v.org_role || null, reports_to: v.reports_to || null,
      computer_mode: v.computer_mode, can_create_bots: v.can_create_bots, team_memory_access: v.team_memory_access,
      budget: v.budget === '' ? {} : { max_cost_per_day: Number(v.budget) } };
    if (local) { out.label = v.label; out.notify = v.notify; }
    return out;
  };
  // Existing bots save as you go (like Grok); new bots are created with the button.
  const commit = async (v = f) => {
    if (!bot) return;
    setErr('');
    try { const b = await api(`/bots/${bot.id}`, { method: 'PATCH', body: body(v) }); setBot(b); ws.reload(); setSaved(true); setTimeout(() => setSaved(false), 1200); }
    catch (e) { setErr(e.message); }
  };
  const create = async () => {
    setErr('');
    try { const b = await api('/bots', { method: 'POST', body: body(f) }); setBot(b); ws.reload(); }
    catch (e) { setErr(e.message); }
  };
  const update = (patch) => { const v = { ...f, ...patch }; setF(v); commit(v); };
  const action = async (path, method = 'POST') => { setMenu(false); await api(`/bots/${bot.id}${path}`, { method }); ws.reload(); onDone?.(); };
  const shareTemplate = async () => {
    const x = await api(`/bots/${bot.id}/export`);
    await saveBlob(new Blob([JSON.stringify(x, null, 2)], { type: 'application/json' }), `${bot.handle}.lowbot-template.json`);
  };
  const av = parseAvatar(f.avatar) || { shape: 'pill', color: bot ? colorFor(bot.id) : AVATAR_COLORS[9] };
  const conv = bot && ws.conversations.find((c) => c.kind === 'private' && c.default_bot_id === bot.id);
  const tabs = [['info', 'Info'], ['links', 'Links'], ['media', 'Media'], ['files', 'Files']];

  return (
    <div className="fixed inset-0 z-40 bg-[#141414] flex flex-col lb-page-in">
      <header className="flex items-center justify-between px-4 pb-2 shrink-0" style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}>
        <button aria-label="back" onClick={onDone} className="lb-press h-12 w-12 rounded-full bg-[#2a2a2a] border border-white/10 flex items-center justify-center text-2xl"><FiChevronLeft /></button>
        {saved && <span className="lb-pop text-[13px] text-emerald-400">{'Saved'}</span>}
        {bot && <div className="flex gap-3 relative">
          <button aria-label={'Share'} onClick={shareTemplate} className="lb-press h-12 w-12 rounded-full bg-[#2a2a2a] border border-white/10 flex items-center justify-center text-xl"><FiShare /></button>
          <button aria-label="more" onClick={() => setMenu(!menu)} className="lb-press h-12 w-12 rounded-full bg-[#2a2a2a] border border-white/10 flex items-center justify-center text-xl"><FiMoreHorizontal /></button>
          {menu && <div className="lb-rise lb-stagger absolute right-0 top-14 z-10 min-w-[220px] rounded-2xl bg-[#262626] border border-white/10 shadow-2xl overflow-hidden text-[15px]">
            {[[bot.pinned ? t('unpin') : t('pin'), () => api(`/bots/${bot.id}`, { method: 'PATCH', body: { pinned: !bot.pinned } }).then((b) => { setBot(b); setMenu(false); ws.reload(); })],
              [bot.hidden ? t('unhide') : ('Hide from sidebar'), () => api(`/bots/${bot.id}`, { method: 'PATCH', body: { hidden: !bot.hidden } }).then((b) => { setBot(b); setMenu(false); ws.reload(); })],
              [bot.paused ? t('resume_bot') : t('pause_bot'), () => action(bot.paused ? '/resume' : '/pause')],
              [t('duplicate'), () => action('/duplicate')],
            ].map(([label, fn]) => <button key={label} onClick={fn} className="block w-full text-left px-4 py-3 hover:bg-white/5">{label}</button>)}
            <button onClick={() => confirm(`${t('delete')} ${bot.name}?`) && action('', 'DELETE')} className="block w-full text-left px-4 py-3 text-rose-400 hover:bg-white/5">{t('delete')}</button>
          </div>}
        </div>}
      </header>

      <div className="flex-1 overflow-y-auto min-h-0 px-4" style={{ paddingBottom: 'max(2rem, env(safe-area-inset-bottom))' }} onClick={() => menu && setMenu(false)}>
        {screen && typeof screen === 'object' ? (
          <FileScreen title={screen.kind === 'project' ? `${screen.name}/AGENTS.md` : `${screen.kind}.md`}
            hint={{ soul: 'Who this bot is: purpose, personality and how it works. Sent first in every prompt.',
              agents: 'Handoff from the last session: exact goal, done, next steps, open questions. Saving replaces the whole file.',
              project: 'Only how an agent should behave while working on this project (conventions, commands, do/don’t).' }[screen.kind]}
            value={screen.value} onBack={() => { screen.reload?.(); setScreen(null); }}
            onSave={(v) => (screen.kind === 'project'
              ? api(`/projects/${encodeURIComponent(screen.name)}/agents`, { method: 'POST', body: { content: v } })
              : api(`/bots/${bot.id}/mind/${screen.kind}`, { method: 'POST', body: { content: v } }))}
            extra={screen.kind === 'agents' ? <Button onClick={() => api(`/bots/${bot.id}/mind/agents`, { method: 'POST', body: { content: '' } }).then(() => { screen.reload?.(); setScreen(null); })}>Clear</Button> : null} />
        ) : screen === 'instructions' ? <InstructionsScreen f={f} set={set} pl={pl} onBack={() => setScreen(null)} save={() => { commit(); setScreen(null); }} /> : <>
          <div className="flex justify-center py-4"><span key={f.avatar} className="lb-pop"><ShapeIcon shape={av.shape} color={av.color} size={150} /></span></div>
          <div className={cls(card, 'overflow-hidden')}>
            <input aria-label={t('name')} value={f.name} onChange={set('name')} onBlur={() => bot && f.name.trim() && commit()}
              className="w-full bg-transparent text-center text-[26px] font-semibold py-4 outline-none" />
            {local && <input aria-label={'Title'} value={f.label} onChange={set('label')} onBlur={() => bot && commit()} placeholder={'Title (optional)'}
              className="w-full bg-transparent text-center text-[17px] text-zinc-300 placeholder:text-zinc-500 py-3.5 border-t border-white/10 outline-none" />}
          </div>

          {bot && <nav className="relative grid grid-cols-4 mt-6 border-b border-white/10">
            {tabs.map(([k, label]) => (
              <button key={k} onClick={() => setTab(k)} className={cls('py-3 text-[17px] transition-colors', tab === k ? 'text-white' : 'text-zinc-500')}>{label}</button>))}
            <span className="absolute bottom-0 h-[3px] w-1/4 transition-transform duration-300 ease-out" style={{ transform: `translateX(${tabs.findIndex((x) => x[0] === tab) * 100}%)` }}>
              <span className="block mx-auto h-full w-3/4 rounded-full bg-white" /></span>
          </nav>}

          {(tab === 'info' || !bot) ? <div key="info" className="lb-rise">
            <Label>{'Character'}</Label>
            <div className={cls(card, 'p-5')}>
              <div className="grid grid-cols-4 gap-y-4 justify-items-center">
                {Object.keys(SHAPES).map((s) => (
                  <button key={s} aria-label={s} onClick={() => update({ avatar: `shape:${s}:${av.color}` })}
                    className={cls('lb-press rounded-2xl p-1.5 transition', av.shape === s && parseAvatar(f.avatar) ? 'ring-2 ring-zinc-400' : '')}>
                    <ShapeIcon shape={s} color={av.color} size={52} eyes={false} /></button>))}
              </div>
              <div className="flex flex-wrap justify-center gap-3.5 mt-6">
                {AVATAR_COLORS.map((c) => (
                  <button key={c} aria-label={c} onClick={() => update({ avatar: `shape:${av.shape}:${c}` })}
                    className={cls('lb-press h-11 w-11 rounded-full transition', av.color.toLowerCase() === c ? 'ring-2 ring-offset-2 ring-offset-[#1f1f1f] ring-zinc-300' : '')} style={{ background: c }} />))}
              </div>
              <div className="border-t border-white/10 mt-5 pt-4">
                <button onClick={() => update({ avatar: '🤖' })} className="text-sky-400 text-[17px]">{'Reset to default'}</button></div>
            </div>
            <Caption>{'How this Bot looks everywhere'}</Caption>

            <Label>{'Model'}</Label>
            <ModelPicker f={f} setF={setF} providers={providers} pl={pl} onCommit={(v) => commit(v)} />

            {!(bot && local) && <div className="mt-5"><Row icon={<FiFileText />} label="Instructions" onClick={() => setScreen('instructions')} /></div>}
            {bot && local && <><Label>Memory</Label><MemoryFiles bot={bot} tick={ws.tick} open={setScreen} /></>}

            {bot && <><Label>{'Routines'}</Label><Routines bot={bot} pl={pl} tick={ws.tick} /></>}

            {bot && local && <>
              <div className={cls(card, 'mt-5 flex items-center justify-between px-5 py-4')}>
                <span className="text-[17px]">{'Notifications'}</span>
                <Toggle on={f.notify} label="notify" onChange={(on) => update({ notify: on })} />
              </div>
              <Caption>{'Get notified when this Bot finishes or needs an answer'}</Caption>
            </>}

            {bot && <div className="mt-5"><Row icon={<FiShare className="text-sky-400" />} label={<span className="text-sky-400">{'Share as template'}</span>} onClick={shareTemplate} right={<span />} /></div>}

            <div className="mt-5"><Advanced f={f} set={set} ws={ws} bot={bot} pl={pl} onSave={bot ? () => commit() : null} /></div>

            {!bot && <button onClick={create} className="lb-press mt-6 w-full rounded-full bg-white text-black py-4 text-[17px] font-semibold">{'Create bot'}</button>}
            {err && <div className="text-sm text-rose-300 mt-3">{err}</div>}
          </div> : <div key={tab} className="lb-rise mt-4"><MediaTabs tab={tab} conversationId={conv?.id} pl={pl} tick={ws.tick} /></div>}
        </>}
      </div>
    </div>
  );
}

export function GroupCreator({ ws, onDone }) {
  const { t } = useT();
  const [sel, setSel] = useState([]);
  const [title, setTitle] = useState('');
  const [err, setErr] = useState('');
  const create = async () => {
    try { const c = await api('/conversations', { method: 'POST', body: { kind: 'group', bot_ids: sel, title } }); ws.reload(); onDone?.(c); }
    catch (e) { setErr(e.message); }
  };
  return (
    <Section title={t('newGroup')}>
      <input className={`${inputCls} mb-2`} placeholder={t('name')} value={title} onChange={(e) => setTitle(e.target.value)} />
      <div className="max-h-64 overflow-y-auto">{ws.bots.map((b) => (
        <label key={b.id} className="flex items-center gap-2 py-1.5 text-sm min-h-[40px]"><input type="checkbox" checked={sel.includes(b.id)}
          onChange={(e) => setSel(e.target.checked ? [...sel, b.id] : sel.filter((x) => x !== b.id))} />{botLabel(b)} <span className="text-xs text-zinc-500">@{b.handle}</span></label>))}</div>
      {err && <div className="text-xs text-rose-300">{err}</div>}
      <div className="flex gap-2 mt-2"><Button kind="primary" disabled={!sel.length} onClick={create}>{t('create')}</Button>{onDone && <Button onClick={() => onDone()}>{t('cancel')}</Button>}</div>
    </Section>
  );
}
