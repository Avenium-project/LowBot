'use client';
// The bots' computer: a live view of each bot's browser with who-is-in-control status,
// Take over / Give back, and Teach-a-task from a recording. On the phone, Take over opens
// the real page full-screen (native); on a server install you drive it from here.
import { useCallback, useEffect, useRef, useState } from 'react';
import { FiArrowRight, FiLock, FiMonitor, FiRefreshCw } from 'react-icons/fi';
import { api, fetchBlobUrl, isLocal } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { BotBlob, Button, Empty, Section, cls, inputCls } from './ui';

function hostOf(url) {
  try { return new URL(url).host; } catch { return url || ''; }
}

function StatusChip({ s }) {
  const human = s.controller?.startsWith('human:');
  const [label, tone] = human ? ['You are in control', 'bg-amber-500/15 text-amber-400']
    : s.status === 'needs_human' ? ['Needs you', 'bg-amber-500/15 text-amber-400 lb-attention']
      : s.busy ? ['Bot is working', 'bg-emerald-500/15 text-emerald-400']
        : s.live ? ['Idle', 'bg-white/5 text-zinc-400'] : ['Closed', 'bg-white/5 text-zinc-500'];
  return (
    <span className={cls('inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[13px] font-medium', tone)}>
      {s.busy && !human ? <span className="lb-typing inline-flex gap-0.5" aria-hidden="true"><span /><span /><span /></span>
        : <span className="h-1.5 w-1.5 rounded-full bg-current" />}
      {label}
    </span>
  );
}

function LiveSurface({ s, ws, reload }) {
  const { t } = useT();
  const local = isLocal();
  const [img, setImg] = useState(null);
  const [err, setErr] = useState('');
  const [text, setText] = useState('');
  const [url, setUrl] = useState('');
  const [skillName, setSkillName] = useState('');
  const [taught, setTaught] = useState('');
  const imgRef = useRef(null);
  const human = s.controller.startsWith('human:');
  const bot = ws.bots.find((b) => b.id === s.bot_id);

  useEffect(() => {
    let alive = true;
    let prev;
    const loop = async () => {
      while (alive) {
        if (document.visibilityState === 'visible' && s.live) {
          try {
            const u = await fetchBlobUrl(`/computers/surfaces/${s.id}/screenshot`);
            if (!alive) { URL.revokeObjectURL(u); return; }
            setImg(u); setErr('');
            if (prev) URL.revokeObjectURL(prev);
            prev = u;
          } catch { /* page not laid out yet */ }
        }
        await new Promise((r) => setTimeout(r, s.busy || human ? 900 : 2000));
      }
    };
    loop();
    return () => { alive = false; if (prev) URL.revokeObjectURL(prev); };
  }, [s.id, s.live, s.busy, human]);

  const act = (path, body) => api(`/computers/surfaces/${s.id}/${path}`, { method: 'POST', body }).then(reload).catch((e) => setErr(e.message));
  const takeOver = () => act('takeover');
  const giveBack = () => act('resume');
  const input = (body) => api(`/computers/surfaces/${s.id}/input`, { method: 'POST', body }).catch((e) => setErr(e.message));
  const tap = (e) => {
    if (local) { if (!human) takeOver(); return; }
    if (!human || !imgRef.current) return;
    const r = imgRef.current.getBoundingClientRect();
    input({ type: 'click', x: ((e.clientX - r.left) / r.width) * imgRef.current.naturalWidth, y: ((e.clientY - r.top) / r.height) * imgRef.current.naturalHeight });
  };
  const teach = async () => {
    try {
      const sk = await api('/skills/teach', { method: 'POST', body: { bot_id: s.bot_id, name: skillName || 'Taught task', consent: true } });
      setTaught(`/${sk.slug}`);
    } catch (e) { setErr(e.message); }
  };

  return (
    <div className={cls('lb-rise rounded-[22px] bg-[#1f1f1f] p-3', s.status === 'needs_human' && !human && 'lb-attention')}>
      <div className="flex items-center gap-3 px-1 pb-3">
        <BotBlob bot={bot} size={40} busy={s.busy} />
        <div className="flex-1 min-w-0">
          <div className="text-[17px] font-medium truncate">{bot?.name || 'Bot'}</div>
          <StatusChip s={s} />
        </div>
      </div>

      {/* Browser frame */}
      <div className="rounded-[18px] overflow-hidden bg-[#141414] border border-white/5">
        <div className="flex items-center gap-2 px-3 py-2 bg-[#2a2a2a]">
          <FiLock className={cls('shrink-0 text-[12px]', s.url?.startsWith('https:') ? 'text-emerald-400' : 'text-zinc-500')} />
          <span className="flex-1 min-w-0 truncate text-[13px] text-zinc-300">{hostOf(s.url) || 'about:blank'}</span>
          {s.recording && <span className="text-[12px] text-rose-400 animate-pulse">● REC</span>}
        </div>
        <button type="button" onClick={tap} className="relative block w-full bg-[#0f0f0f] group" aria-label={local ? t('takeOver') : t('liveView')}>
          {img ? <img ref={imgRef} src={img} alt={t('liveView')} className="lb-backdrop-in block w-full h-auto max-h-[64vh] object-contain object-top" />
            : <div className="relative aspect-[9/14] max-h-[50vh] w-full flex flex-col items-center justify-center gap-2 text-zinc-500 text-[14px]">
                {s.live ? <span className="lb-skeleton absolute inset-0" /> : <><FiMonitor className="text-3xl" />Browser closed</>}
              </div>}
          {local && !human && img && (
            <span className="absolute inset-x-0 bottom-0 flex justify-center pb-4 pt-10 bg-gradient-to-t from-black/70 to-transparent opacity-0 group-active:opacity-100 group-hover:opacity-100 transition-opacity">
              <span className="rounded-full bg-white text-black px-4 py-2 text-[14px] font-medium">Tap to take over</span>
            </span>)}
          {human && <span className="absolute top-3 right-3 rounded-full bg-amber-400 text-black px-3 py-1 text-[12px] font-semibold">YOU</span>}
        </button>
      </div>

      {/* Server install: drive the page from here while you have control. */}
      {human && !local && (
        <div className="lb-rise mt-3 space-y-2">
          <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (url) input({ type: 'navigate', url: /^https?:/.test(url) ? url : `https://${url}` }); }}>
            <input className={inputCls} value={url} onChange={(e) => setUrl(e.target.value)} placeholder="Go to URL" />
            <Button type="submit"><FiArrowRight /></Button>
          </form>
          <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (text) { input({ type: 'type', text }); setText(''); } }}>
            <input className={inputCls} value={text} onChange={(e) => setText(e.target.value)} placeholder="Type into the page" />
            <Button type="submit">Type</Button>
          </form>
          <div className="flex gap-2 flex-wrap">
            {['Enter', 'Tab', 'Escape', 'Backspace'].map((k) => <Button small key={k} onClick={() => input({ type: 'key', key: k })}>{k}</Button>)}
            <Button small onClick={() => input({ type: 'scroll', dy: -500 })}>↑</Button>
            <Button small onClick={() => input({ type: 'scroll', dy: 500 })}>↓</Button>
          </div>
          <div className="text-[12px] text-zinc-500 px-1">Tap the screenshot to click. Sign in, 2FA and CAPTCHAs are yours to do — the bot never bypasses them.</div>
        </div>
      )}

      {err && <div className="text-[13px] text-rose-400 mt-2 px-1">{err}</div>}

      <div className="flex gap-2 mt-3">
        {human
          ? <Button kind="primary" className="flex-1" onClick={giveBack}>{t('giveBack')}</Button>
          : <Button kind="primary" className="flex-1" onClick={takeOver}>{t('takeOver')}</Button>}
        <Button onClick={reload} title="Refresh"><FiRefreshCw /></Button>
      </div>

      {local && !human && s.recorded_steps > 0 && (
        <div className="lb-rise mt-3 rounded-2xl bg-[#2a2a2a] p-3 space-y-2">
          <div className="text-[14px]">{s.recorded_steps} steps recorded. Turn them into a skill draft you can review:</div>
          <div className="flex gap-2"><input className={inputCls} value={skillName} onChange={(e) => setSkillName(e.target.value)} placeholder="Skill name" />
            <Button kind="primary" onClick={teach}>Teach</Button></div>
          {taught && <div className="text-[13px] text-emerald-400">Created {taught} — use it in chat by typing {taught}</div>}
        </div>
      )}
    </div>
  );
}

// A bot's Linux shell: live log, and the owner can type commands into the same shell.
function TerminalCard({ ws, botId }) {
  const [t, setT] = useState(null);
  const [cmd, setCmd] = useState('');
  const [running, setRunning] = useState(false);
  const [err, setErr] = useState('');
  const ref = useRef(null);
  const bot = ws.bots.find((b) => b.id === botId);
  const load = useCallback(() => api(`/linux/sessions/${botId}`).then(setT).catch(() => {}), [botId]);
  useEffect(() => {
    load();
    const h = setInterval(() => { if (document.visibilityState === 'visible') load(); }, 1500);
    return () => clearInterval(h);
  }, [load]);
  useEffect(() => { if (ref.current) ref.current.scrollTop = ref.current.scrollHeight; }, [t?.log]);
  const run = async (e) => {
    e.preventDefault();
    if (!cmd.trim() || running) return;
    setRunning(true); setErr('');
    const c = cmd;
    setCmd('');
    try { await api(`/linux/sessions/${botId}/run`, { method: 'POST', body: { command: c } }); } catch (x) { setErr(x.message); }
    setRunning(false); load();
  };
  if (!t) return null;
  const busy = t.busy || running;
  return (
    <div className="lb-rise rounded-[22px] bg-[#1f1f1f] p-3">
      <div className="flex items-center gap-3 px-1 pb-3">
        <BotBlob bot={bot} size={36} busy={busy} />
        <div className="flex-1 min-w-0">
          <div className="text-[16px] font-medium truncate">{bot?.name || 'Bot'} · terminal</div>
          <div className={cls('text-[12px]', busy ? 'text-emerald-400' : 'text-zinc-500')}>{busy ? 'running a command…' : t.alive ? `idle · ${t.cwd}` : 'not started'}</div>
        </div>
        <Button small onClick={() => api(`/linux/sessions/${botId}/reset`, { method: 'POST' }).then(setT)}>Restart</Button>
      </div>
      <pre ref={ref} className="lb-selectable max-h-[45vh] min-h-[120px] overflow-auto rounded-t-[16px] bg-black p-3 text-[12px] leading-[1.45] text-zinc-200 whitespace-pre-wrap break-words font-mono">
        {t.log || 'No commands yet.'}
      </pre>
      <form onSubmit={run} className="flex items-center gap-2 rounded-b-[16px] bg-black border-t border-white/10 px-3 py-2 font-mono text-[13px]">
        <span className="text-emerald-400">$</span>
        <input value={cmd} onChange={(e) => setCmd(e.target.value)} disabled={busy} placeholder={busy ? 'wait for the current command…' : 'type a command (you, not the bot)'}
          autoCapitalize="off" autoCorrect="off" spellCheck={false} className="flex-1 min-w-0 bg-transparent outline-none text-zinc-100 placeholder:text-zinc-600" />
        <button type="submit" disabled={busy || !cmd.trim()} className="text-zinc-300 disabled:opacity-30"><FiArrowRight /></button>
      </form>
      {err && <div className="text-[12px] text-rose-400 px-1 pt-2">{err}</div>}
      <div className="text-[11px] text-zinc-600 px-1 pt-2">Commands you type run in the bot&apos;s shell right away and are recorded in the audit log. The bot&apos;s own commands still ask for approval.</div>
    </div>
  );
}

function Terminals({ ws, botId }) {
  const [st, setSt] = useState(null);
  useEffect(() => { api('/linux').then(setSt).catch(() => setSt(null)); }, [ws.tick]);
  if (!st?.installed) return null;
  const withShell = (st.sessions || []).map((s) => s.bot_id);
  const ids = botId ? [botId] : withShell;
  if (!ids.length) return null;
  return (
    <Section title="Terminals">
      <div className="space-y-4">{ids.map((id) => <TerminalCard key={id} ws={ws} botId={id} />)}</div>
    </Section>
  );
}

export default function ComputerView({ ws, botId }) {
  const local = isLocal();
  const [data, setData] = useState(null);
  const [err, setErr] = useState('');
  const load = useCallback(() => api('/computers/surfaces').then((d) => { setData(d); setErr(''); }).catch((e) => setErr(e.message)), []);
  useEffect(() => { load(); }, [load, ws.tick]);
  useEffect(() => { const h = setInterval(load, 4000); return () => clearInterval(h); }, [load]);
  const open = (id) => api('/computers/open', { method: 'POST', body: { bot_id: id } }).then(load).catch((e) => setErr(e.message));
  if (err && !data) return <Empty>{err}</Empty>;
  if (!data) return <div className="lb-skeleton h-[60vh] rounded-[22px]" />;
  const list = botId ? data.surfaces.filter((s) => s.bot_id === botId) : data.surfaces;
  const withoutBrowser = ws.bots.filter((b) => !b.hidden && !data.surfaces.some((s) => s.bot_id === b.id) && (!botId || b.id === botId));
  return (
    <div>
      <div className="text-[14px] text-zinc-500 px-2 mb-4 leading-snug">{local
        ? 'Each bot has its own tab in a browser on this phone (cookies are shared). Take over to sign in or finish a step yourself, then give control back.'
        : `Live view of the bots' browsers · up to ${data.max_active_surfaces} open at once.`}</div>
      {local && <Terminals ws={ws} botId={botId} />}
      {list.length > 0 && <div className="space-y-4 mb-6">{list.map((s) => <LiveSurface key={s.id} s={s} ws={ws} reload={load} />)}</div>}
      {local && withoutBrowser.length > 0 && (
        <Section title={list.length ? 'Other bots' : 'Open a browser'}>
          <div className="rounded-[22px] bg-[#1f1f1f] overflow-hidden lb-stagger">
            {withoutBrowser.map((b) => (
              <div key={b.id} className="flex items-center gap-3 px-4 py-3 border-b border-white/5 last:border-b-0">
                <BotBlob bot={b} size={36} />
                <span className="flex-1 truncate text-[16px]">{b.name}</span>
                <Button small onClick={() => open(b.id)}>Open browser</Button>
              </div>))}
          </div>
        </Section>
      )}
      {!list.length && !(local && withoutBrowser.length) && <Empty>No browser open yet — it starts when a bot browses the web.</Empty>}
      {err && <div className="text-[13px] text-rose-400 px-2">{err}</div>}
    </div>
  );
}
