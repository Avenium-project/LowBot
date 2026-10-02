'use client';
// Widgets bots make for the user: a card in the chat ("Add to home"), cards on LowBot's home
// screen, and the Widgets settings page. The phone's home screen gets them through Android widgets.
import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { FiHome, FiMoreHorizontal, FiSmartphone, FiTrash2 } from 'react-icons/fi';
import { api, isLocal } from '../../lib/v2/api';
import { BotBlob, Toggle, askConfirm, cls, shortTime } from './ui';

const MD = { table: ({ children }) => <div className="lb-table"><table>{children}</table></div> };

function Body({ w, clamp }) {
  return (
    <div className={cls('lb-md prose prose-invert max-w-none prose-p:my-0.5 prose-ul:my-0.5 prose-li:my-0 prose-headings:my-1 text-[14px] leading-snug text-zinc-200', clamp && 'max-h-[168px] overflow-hidden')}>
      {w.content ? <ReactMarkdown remarkPlugins={[remarkGfm]} components={MD}>{w.content}</ReactMarkdown> : <span className="text-zinc-500">Empty</span>}
    </div>
  );
}

export function pinToPhone(id) {
  return Boolean(window.LowBotNative?.pinWidget?.(id));
}

/** The card shown in the chat when a bot made a widget. */
export function WidgetChatCard({ id, ws }) {
  const [w, setW] = useState(null);
  const [note, setNote] = useState('');
  useEffect(() => { api(`/widgets/${id}`).then(setW).catch(() => setW(false)); }, [id, ws.tick]);
  if (w === false) return <div className="text-center text-[13px] text-zinc-500 my-2">Widget deleted</div>;
  if (!w) return null;
  const bot = ws.bots.find((b) => b.id === w.bot_id);
  const home = () => api(`/widgets/${id}`, { method: 'PATCH', body: { on_home: !w.on_home } }).then((x) => { setW(x); ws.reload(); });
  return (
    <div className="lb-rise my-3 rounded-[22px] bg-[#1f1f1f] border border-white/10 p-4">
      <div className="flex items-center gap-2 mb-2">
        {bot && <BotBlob bot={bot} size={22} still />}
        <span className="flex-1 truncate text-[16px] font-semibold">{w.title}</span>
        <span className="text-[11px] uppercase tracking-wide text-zinc-500">Widget</span>
      </div>
      <Body w={w} clamp />
      <div className="mt-3 grid grid-cols-2 gap-2">
        <button type="button" onClick={home} className={cls('lb-press rounded-xl py-2.5 text-[14px] font-medium', w.on_home ? 'bg-[#3a3a3c] text-zinc-200' : 'bg-white text-black')}>
          {w.on_home ? 'On home ✓' : 'Add to home'}</button>
        {isLocal() && <button type="button" onClick={() => setNote(pinToPhone(id) ? 'Confirm on your phone' : 'Your launcher does not support this — add “Bot widget” from the widget list')}
          className="lb-press rounded-xl bg-[#3a3a3c] py-2.5 text-[14px]">Add to phone</button>}
      </div>
      {note && <div className="mt-2 text-[12px] text-zinc-400 text-center">{note}</div>}
    </div>
  );
}

/** Widgets on LowBot's home screen, above the chats. */
export function HomeWidgets({ ws, onOpenBot }) {
  const [list, setList] = useState([]);
  const [menu, setMenu] = useState(null);
  const load = () => api('/widgets').then((d) => setList(d.widgets.filter((w) => w.on_home))).catch(() => {});
  useEffect(() => { if (isLocal()) load(); }, [ws.tick]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!list.length) return null;
  return (
    <div className="px-3 pt-1 pb-3 space-y-2.5 lb-stagger">
      {list.map((w) => {
        const bot = ws.bots.find((b) => b.id === w.bot_id);
        return (
          <div key={w.id} className="relative rounded-[22px] bg-[#1f1f1f] p-4">
            <button type="button" onClick={() => bot && onOpenBot(bot)} className="lb-press block w-full text-left">
              <div className="flex items-center gap-2 mb-1.5 pr-8">
                {bot && <BotBlob bot={bot} size={22} still />}
                <span className="flex-1 truncate text-[16px] font-semibold">{w.title}</span>
                <span className="text-[12px] text-zinc-500">{shortTime(w.updated_at)}</span>
              </div>
              <Body w={w} clamp />
            </button>
            <button type="button" aria-label="Widget options" onClick={() => setMenu(menu === w.id ? null : w.id)}
              className="lb-press absolute top-3 right-3 h-8 w-8 rounded-full flex items-center justify-center text-zinc-400"><FiMoreHorizontal /></button>
            {menu === w.id && (
              <div className="lb-rise absolute right-3 top-12 z-10 min-w-[210px] rounded-2xl bg-[#2a2a2a] border border-white/10 shadow-2xl overflow-hidden text-[15px]">
                <button type="button" onClick={() => { setMenu(null); pinToPhone(w.id); }} className="flex w-full items-center gap-3 px-4 py-3 hover:bg-white/5"><FiSmartphone /> Add to phone</button>
                <button type="button" onClick={() => { setMenu(null); api(`/widgets/${w.id}`, { method: 'PATCH', body: { on_home: false } }).then(load); }}
                  className="flex w-full items-center gap-3 px-4 py-3 hover:bg-white/5"><FiHome /> Remove from home</button>
                <button type="button" onClick={async () => { setMenu(null); if (await askConfirm({ title: `Delete “${w.title}”?`, confirmLabel: 'Delete', danger: true })) api(`/widgets/${w.id}`, { method: 'DELETE' }).then(load); }}
                  className="flex w-full items-center gap-3 px-4 py-3 text-rose-400 hover:bg-white/5"><FiTrash2 /> Delete</button>
              </div>)}
          </div>);
      })}
    </div>
  );
}

/** Settings → Widgets */
export function WidgetsSettings({ ws }) {
  const [d, setD] = useState(null);
  const [note, setNote] = useState('');
  const load = () => api('/widgets').then(setD).catch(() => {});
  useEffect(() => { load(); }, [ws.tick]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!d) return null;
  const group = 'rounded-[22px] bg-[#1f1f1f] overflow-hidden';
  const row = 'flex items-center gap-4 px-5 min-h-[56px] py-3 border-b border-white/5 last:border-b-0';
  return (
    <>
      <div className={group}>
        <div className={row}><span className="flex-1 text-[17px]">Bots can make widgets</span>
          <Toggle on={d.bots_may_create} label="Bots can make widgets" onChange={(v) => api('/widgets/settings', { method: 'POST', body: { bots_may_create: v } }).then(load)} /></div>
        {isLocal() && <button type="button" className={cls(row, 'lb-press w-full text-left')}
          onClick={() => setNote(window.LowBotNative?.pinBotsWidget?.() ? 'Confirm on your phone' : 'Long-press your home screen → Widgets → LowBot → “Your bots”')}>
          <span className="flex-1 text-[17px] text-sky-400">Add “Your bots” to phone</span></button>}
      </div>
      {note && <div className="px-5 pt-2 text-[13px] text-zinc-400">{note}</div>}
      <div className="px-5 pt-5 pb-2 text-[14px] text-zinc-500">Widgets</div>
      <div className={group}>
        {d.widgets.length ? d.widgets.map((w) => {
          const bot = ws.bots.find((b) => b.id === w.bot_id);
          return (
            <div key={w.id} className={row}>
              {bot ? <BotBlob bot={bot} size={28} still /> : <span className="w-7" />}
              <span className="flex-1 min-w-0 truncate text-[17px]">{w.title}</span>
              <Toggle on={w.on_home} label={`Home: ${w.title}`} onChange={(v) => api(`/widgets/${w.id}`, { method: 'PATCH', body: { on_home: v } }).then(load)} />
              <button type="button" aria-label={`Delete ${w.title}`} className="lb-press text-zinc-500 px-1"
                onClick={async () => { if (await askConfirm({ title: `Delete “${w.title}”?`, confirmLabel: 'Delete', danger: true })) api(`/widgets/${w.id}`, { method: 'DELETE' }).then(load); }}><FiTrash2 /></button>
            </div>);
        }) : <div className="px-5 py-4 text-[16px] text-zinc-500">None yet</div>}
      </div>
    </>
  );
}
