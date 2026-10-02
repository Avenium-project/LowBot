'use client';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import { FiArrowUp, FiCheckCircle, FiClipboard, FiChevronLeft, FiClock, FiLock, FiMic, FiMonitor, FiPaperclip, FiPlus, FiSquare, FiStopCircle, FiXCircle } from 'react-icons/fi';
import { api, downloadPath, fetchBlobUrl, isLocal, pendingOutbox, sendMessage } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { ElicitationCard } from './InboxPanel';
import { FileIcon, FileViewer, fmtSize } from './Files';
import { WidgetChatCard } from './Widgets';
import { BotBlob, cls } from './ui';

const BUSY = ['working', 'queued', 'retrying', 'waiting'];

function dayLabel(iso, lang) {
  const d = new Date(iso);
  const now = new Date();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  const time = d.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return `${'Today'} ${time}`;
  if (d.toDateString() === y.toDateString()) return `${'Yesterday'} ${time}`;
  return d.toLocaleString(lang, { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
}

// Markdown: GFM tables, task lists, strikethrough; links open outside the app; tables scroll sideways.
const MD = {
  a: ({ href, children }) => <a href={href} target="_blank" rel="noopener noreferrer" className="text-sky-400 underline underline-offset-2">{children}</a>,
  table: ({ children }) => <div className="lb-table"><table>{children}</table></div>,
  pre: CodeBlock,
};

// Code blocks scroll sideways (keys and commands stay intact) and have a Copy button.
function CodeBlock({ children }) {
  const ref = useRef(null);
  const [done, setDone] = useState(false);
  const copy = async () => {
    const text = ref.current?.innerText || '';
    try { await navigator.clipboard.writeText(text); } catch {
      const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove();
    }
    setDone(true); setTimeout(() => setDone(false), 1500);
  };
  return (
    <div className="lb-code">
      <pre ref={ref}>{children}</pre>
      <button type="button" onClick={copy} className="lb-code-copy">{done ? 'Copied' : 'Copy'}</button>
    </div>
  );
}

const isImage = (a) => a.kind === 'image' || /^image\//.test(a.mime || '') || /\.(png|jpe?g|gif|webp)$/i.test(a.name || '');

// Images show as a rounded thumbnail, other files as a compact card; tapping either opens the viewer
// (preview, Open in another app, Save to Downloads, Share).
function Attachment({ a }) {
  const [url, setUrl] = useState(null);
  const [open, setOpen] = useState(false);
  const image = isImage(a);
  useEffect(() => {
    if (!image || !a.artifact_id) return undefined;
    let u;
    fetchBlobUrl(`/artifacts/${a.artifact_id}/download`).then((x) => { u = x; setUrl(x); }).catch(() => {});
    return () => { if (u) URL.revokeObjectURL(u); };
  }, [a.artifact_id, image]);
  if (!a.artifact_id) return null;
  const viewer = open && <FileViewer file={{ path: `/artifacts/${a.artifact_id}/download`, name: a.name || 'file', mime: a.mime, size: a.size }} onClose={() => setOpen(false)} />;
  if (image) {
    return (
      <>
        <button type="button" onClick={() => setOpen(true)} className="lb-press block mt-1 overflow-hidden rounded-[18px] bg-black/30" aria-label={a.name || 'image'}>
          {url ? <img src={url} alt={a.name || 'image'} className="lb-backdrop-in block max-h-[320px] w-auto max-w-full object-contain" />
            : <span className="lb-skeleton block h-[200px] w-[160px]" />}
        </button>
        {viewer}
      </>
    );
  }
  return (
    <>
      <button type="button" onClick={() => setOpen(true)} className="lb-press mt-1 flex w-full max-w-[280px] items-center gap-3 rounded-[16px] bg-black/25 px-3 py-2.5 text-left">
        <FileIcon name={a.name || ''} mime={a.mime} />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[14px] text-zinc-100">{a.name || 'file'}</span>
          <span className="block text-[12px] text-zinc-500">{fmtSize(a.size) || 'Tap to open'}</span>
        </span>
      </button>
      {viewer}
    </>
  );
}

// Grok-style secure secret request: the value goes to the encrypted vault, never into the chat or the model.
function SecretCard({ m }) {
  const { lang } = useT();
  const [value, setValue] = useState('');
  const [state, setState] = useState('');
  const req = m.meta.secret_request;
  const save = async (e) => {
    e.preventDefault();
    try { await api(`/tasks/${m.task_id}/secret`, { method: 'POST', body: { value } }); setValue(''); setState('ok'); }
    catch (err) { setState(err.message); }
  };
  return (
    <div className="lb-rise my-2 rounded-[22px] bg-[#262626] px-4 py-4">
      <div className="flex items-center gap-2 text-[17px] font-semibold text-white"><FiLock /> {'Secure secret request'}</div>
      <div className="text-[15px] text-zinc-300 mt-1">{req.description} <span className="text-zinc-500">({req.name})</span></div>
      {state === 'ok' ? <div className="mt-3 rounded-xl py-2.5 text-center bg-emerald-900/40 text-emerald-400">{'Saved in the phone vault'}</div> : (
        <form onSubmit={save} className="mt-3 flex gap-2">
          <input type="password" autoComplete="off" className="flex-1 min-w-0 rounded-xl bg-black/40 px-3 py-2.5 outline-none text-zinc-100" value={value} onChange={(e) => setValue(e.target.value)}
            placeholder={'Value (hidden from the bot)'} />
          <button disabled={!value} className="rounded-xl bg-white text-black px-4 font-medium disabled:opacity-50">{'Save'}</button>
        </form>)}
      {state && state !== 'ok' && <div className="text-xs text-rose-300 mt-2">{state}</div>}
      <div className="text-[12px] text-zinc-500 mt-2">{'The bot only sees the name, never the value.'}</div>
    </div>
  );
}

function TakeoverCard({ m, onOpenComputer }) {
  const { lang } = useT();
  return (
    <div className="lb-rise lb-attention my-2 rounded-[22px] bg-[#262626] px-4 py-4">
      <div className="flex items-center gap-2 text-[17px] font-semibold text-white"><FiMonitor /> {'The bot needs you on the computer'}</div>
      <div className="text-[15px] text-zinc-300 mt-1 whitespace-pre-wrap">{m.text.replace(/^🖥️\s*/, '')}</div>
      <button onClick={() => onOpenComputer(m.meta.takeover_request)} className="mt-3 w-full rounded-xl bg-white text-black py-2.5 font-medium">
        {'Take over the computer'}</button>
    </div>
  );
}

function Bubble({ m, bot, showName, onOpenComputer, animate, ws }) {
  if (m.meta?.secret_request && m.author_type === 'bot') return <SecretCard m={m} />;
  if (m.meta?.takeover_request && m.author_type === 'bot') return <TakeoverCard m={m} onOpenComputer={onOpenComputer} />;
  if (m.author_type === 'system' && m.meta?.handoff && /^🔁/.test(m.text || '')) return null; // old "wrote a handoff" notices
  if (m.author_type === 'system' && m.meta?.widget_id && ws) return <WidgetChatCard id={m.meta.widget_id} ws={ws} />;
  if (m.author_type === 'system') {
    return <div className={cls('text-center text-[13px] text-zinc-500 my-2 px-8 whitespace-pre-wrap', animate && 'lb-rise')}>{m.text}</div>;
  }
  const mine = m.author_type === 'user';
  const onlyFile = m.attachments?.length > 0 && (!m.text || /^📎/.test(m.text.trim()) || m.attachments.some((a) => a.name && m.text.trim() === a.name));
  return (
    <div className={cls('flex my-1.5', mine ? 'justify-end' : 'justify-start', animate && (mine ? 'lb-msg-right' : 'lb-msg-left'))}>
      <div className={cls('min-w-0 max-w-[85%] text-[16px] leading-snug break-words [overflow-wrap:anywhere] rounded-[22px]', !mine && 'lb-selectable',
        onlyFile ? 'p-0' : 'px-4 py-3', onlyFile ? '' : mine ? 'bg-[#3a3a3c] text-white' : 'bg-[#262626] text-zinc-100')}>
        {showName && bot && <div className="flex items-center gap-1.5 mb-1 text-[13px] text-zinc-400"><BotBlob bot={bot} size={18} still />{bot.name}</div>}
        {onlyFile ? null : mine ? <div className="whitespace-pre-wrap">{m.text}</div>
          : <div className="lb-md prose prose-invert max-w-none prose-p:my-1 prose-pre:my-2 prose-headings:mt-3 prose-headings:mb-1.5 prose-ul:my-1 prose-ol:my-1 prose-li:my-0.5 text-[16px]"><ReactMarkdown remarkPlugins={[remarkGfm, remarkBreaks]} components={MD}>{m.text}</ReactMarkdown></div>}
        {m.attachments?.length > 0 && <div className={cls('flex flex-col gap-1.5', mine && 'items-end')}>{m.attachments.map((a, i) => <Attachment key={i} a={a} />)}</div>}
      </div>
    </div>
  );
}

// Inline approval card: pending -> buttons; decided -> status bar (e.g. green "Zatwierdzono").
function ApprovalInline({ a, ws, onChanged }) {
  const { t, lang } = useT();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(JSON.stringify(a.display, null, 2));
  const [err, setErr] = useState('');
  const [open, setOpen] = useState(false);
  const decide = async (decision) => {
    setErr('');
    try { await api(`/approvals/${a.id}/decide`, { method: 'POST', body: { decision, args_hash: a.args_hash } }); }
    catch (e) { setErr(e.message); }
    ws.reload(); onChanged();
  };
  const saveEdit = async () => {
    try { await api(`/approvals/${a.id}/edit`, { method: 'POST', body: { arguments: JSON.parse(draft) } }); setEditing(false); ws.reload(); onChanged(); }
    catch (e) { setErr(e.message); }
  };
  const done = {
    approved: ['Approved', 'bg-emerald-900/40 text-emerald-400', FiCheckCircle],
    consumed: ['Approved', 'bg-emerald-900/40 text-emerald-400', FiCheckCircle],
    denied: ['Denied', 'bg-rose-900/40 text-rose-400', FiXCircle],
    expired: ['Expired', 'bg-zinc-800 text-zinc-400', FiClock],
  }[a.status];
  const Icon = done?.[2];
  const description = `${a.summary}${a.effect ? ` — ${a.effect}` : ''}${a.target ? ` (${a.target})` : ''}`;
  return (
    <div className={cls('lb-rise my-2 rounded-[22px] bg-[#262626] px-4 py-4', !done && 'lb-attention')}>
      <div className="text-[17px] font-semibold text-white">{'Approval request'}</div>
      <button onClick={() => setOpen(!open)} className={cls('text-left text-[15px] text-zinc-200 mt-1', !open && 'line-clamp-3')}>{description}</button>
      {a.review?.decision && <div className="mt-2 text-[13px] text-sky-300">Auto Review: {a.review.reason}</div>}
      {open && !editing && <pre className="mt-2 text-xs bg-black/30 rounded-xl p-3 whitespace-pre-wrap break-all max-h-48 overflow-y-auto text-zinc-300">{JSON.stringify(a.display, null, 2)}</pre>}
      {editing && <textarea className="mt-2 w-full rounded-xl bg-black/40 p-3 font-mono text-xs h-32 text-zinc-100 outline-none" value={draft} onChange={(e) => setDraft(e.target.value)} />}
      {err && <div className="text-xs text-rose-300 mt-2">{err}</div>}
      {done ? (
        <div className={cls('lb-pop mt-3 flex items-center justify-center gap-2 rounded-xl py-2.5 text-[15px] font-medium', done[1])}><Icon /> {done[0]}</div>
      ) : editing ? (
        <div className="mt-3 grid grid-cols-2 gap-2">
          <button onClick={saveEdit} className="rounded-xl bg-white text-black py-2.5 font-medium">{t('save')}</button>
          <button onClick={() => setEditing(false)} className="lb-press rounded-xl bg-[#3a3a3c] py-2.5">{t('cancel')}</button>
        </div>
      ) : (
        <div className="mt-3 grid grid-cols-2 gap-2">
          <button onClick={() => decide('approve')} className="lb-press rounded-xl bg-white text-black py-2.5 font-medium">{'Allow once'}</button>
          <button onClick={() => decide('always')} className="lb-press rounded-xl bg-[#3a3a3c] py-2.5">{'Always allow'}</button>
          <button onClick={() => decide('deny')} className="lb-press rounded-xl bg-[#3a3a3c] py-2.5">{t('deny')}</button>
          <button onClick={() => { setOpen(true); setEditing(true); }} className="lb-press rounded-xl bg-[#3a3a3c] py-2.5">{t('edit')}</button>
        </div>
      )}
    </div>
  );
}

// Grok-style "<bot> is working" line: the bot's character plus a shimmering status text.
const WORK_PHRASE = { running: 'is working', queued: 'is working', retry_scheduled: 'is retrying', waiting_dependency: 'is waiting for another bot',
  waiting_approval: 'is waiting for your approval', waiting_input: 'is asking you', paused: 'is paused', unknown_outcome: 'needs you to check a result' };

function WorkingStrip({ task, bot, onAnswer, special }) {
  const { t } = useT();
  const [answer, setAnswer] = useState('');
  const asking = task.run_status === 'waiting_input' && !special;
  const needsYou = ['waiting_input', 'waiting_approval', 'unknown_outcome'].includes(task.run_status);
  const phrase = WORK_PHRASE[task.run_status] || 'is working';
  return (
    <div className="lb-rise my-3">
      <div className="flex items-center gap-3">
        <BotBlob bot={bot} size={30} busy={!needsYou} attention={needsYou} />
        <span className={cls('flex-1 min-w-0 truncate text-[17px]', needsYou ? 'text-amber-400' : 'lb-shimmer-text')}>
          {bot?.name || 'Bot'} {phrase}
        </span>
        <button aria-label={t('stop')} title={t('stop')} onClick={() => api(`/tasks/${task.id}/cancel`, { method: 'POST' })}
          className="lb-press text-zinc-500 hover:text-rose-300 h-9 w-9 flex items-center justify-center rounded-full"><FiStopCircle /></button>
      </div>
      {asking && (
        <form className="flex gap-2 mt-2 pl-[42px]" onSubmit={(e) => { e.preventDefault(); if (answer.trim()) { onAnswer(task.id, answer); setAnswer(''); } }}>
          <input className="flex-1 min-w-0 rounded-full bg-[#2a2a2a] px-4 py-2 outline-none text-zinc-100" value={answer} onChange={(e) => setAnswer(e.target.value)} placeholder={t('answer')} />
          <button type="submit" className="rounded-full bg-white text-black px-4">{t('answer')}</button>
        </form>
      )}
    </div>
  );
}

export default function Conversation({ conversation, ws, onBack, skills, onOpenBot, onOpenComputer }) {
  const { t, lang } = useT();
  const [messages, setMessages] = useState([]);
  const [approvals, setApprovals] = useState([]);
  const [text, setText] = useState('');
  const [attachments, setAttachments] = useState([]);
  const [error, setError] = useState('');
  const [recording, setRecording] = useState(null);
  const [listening, setListening] = useState(false);
  const [level, setLevel] = useState(0);
  const [plusOpen, setPlusOpen] = useState(false);
  const local = isLocal();
  const [firstUnread] = useState(conversation.unread > 0 ? conversation.last_read_seq : null);
  const endRef = useRef(null);
  const fileRef = useRef(null);
  const botsById = useMemo(() => Object.fromEntries(ws.bots.map((b) => [b.id, b])), [ws.bots]);
  const members = conversation.bot_ids.map((id) => botsById[id]).filter(Boolean);
  const lead = members[0];
  const activeAll = ws.tasks.filter((x) => x.conversation_id === conversation.id && !['completed', 'failed', 'cancelled'].includes(x.status));
  // One "is working" line per bot: a task waiting on the user wins, otherwise the newest.
  const activeTasks = Object.values(activeAll.reduce((acc, x) => {
    const cur = acc[x.bot_id];
    const needs = (t) => ['waiting_input', 'waiting_approval', 'unknown_outcome'].includes(t.run_status);
    if (!cur || (needs(x) && !needs(cur))) acc[x.bot_id] = x;
    return acc;
  }, {}));
  const convTaskIds = new Set(ws.tasks.filter((x) => x.conversation_id === conversation.id).map((x) => x.id));
  const elicitations = ws.elicitations.filter((e) => convTaskIds.has(e.task_id));

  const load = useCallback(async () => {
    const [rows, apr] = await Promise.all([
      api(`/conversations/${conversation.id}/messages?limit=500`),
      api('/approvals?status=').catch(() => []),
    ]);
    setMessages(rows);
    setApprovals(apr.filter((a) => a.conversation_id === conversation.id && a.status !== 'invalidated'));
    if (rows.length) api(`/conversations/${conversation.id}/read`, { method: 'POST', body: { seq: rows[rows.length - 1].seq } }).catch(() => {});
  }, [conversation.id]);

  useEffect(() => { load(); }, [load, ws.tick]);
  // Text shared from another app (Android share sheet) lands in the composer.
  useEffect(() => {
    if (window.__lowbotPendingShare) { setText(window.__lowbotPendingShare); window.__lowbotPendingShare = null; }
  }, []);
  useEffect(() => { endRef.current?.scrollIntoView({ block: 'end' }); }, [messages.length, approvals.length, activeTasks.length]);

  // Timeline: messages and approval cards ordered by time.
  const timeline = useMemo(() => messages.map((m) => ({ kind: 'msg', at: m.created_at, m }))
    .concat(approvals.map((a) => ({ kind: 'apr', at: a.created_at, a })))
    .sort((x, y) => x.at.localeCompare(y.at)), [messages, approvals]);

  const token = text.split(/\s/).pop() || '';
  const suggestions = token.startsWith('@')
    ? members.filter((b) => b.handle.startsWith(token.slice(1).toLowerCase())).map((b) => ({ insert: `@${b.handle} `, label: `@${b.handle} · ${b.name}` }))
    : (token.startsWith('/') && text.trim() === token)
      ? skills.filter((s) => s.slug.startsWith(token.slice(1))).map((s) => ({ insert: `/${s.slug} `, label: `/${s.slug} · ${s.name}` }))
      : [];

  const submit = async (e) => {
    e?.preventDefault();
    if (!text.trim() && !attachments.length) return;
    if (attachments.some((a) => a.uploading)) { setError('Wait until the attachments finish uploading.'); return; }
    setError('');
    const body = text;
    const att = attachments.map(({ artifact_id, name, kind }) => ({ artifact_id, name, kind }));
    attachments.forEach((a) => a.preview && URL.revokeObjectURL(a.preview));
    setText(''); setAttachments([]);
    try { await sendMessage(conversation.id, body, att); }
    catch (err) { setError(err.status ? err.message : ('Offline — will send when back online.')); }
    load();
  };

  // Attachments upload right away; images get a thumbnail with ✕ until the message is sent.
  const upload = async (file, kind) => {
    const key = `${Date.now()}_${Math.random()}`;
    const image = file.type.startsWith('image/');
    const preview = image ? URL.createObjectURL(file) : null;
    setAttachments((xs) => [...xs, { key, name: file.name, kind: kind || (image ? 'image' : 'file'), preview, uploading: true }]);
    try {
      const form = new FormData();
      form.append('file', file);
      const art = await api(`/artifacts/upload?conversation_id=${conversation.id}`, { method: 'POST', form });
      setAttachments((xs) => xs.map((a) => (a.key === key ? { ...a, artifact_id: art.id, name: art.name, uploading: false } : a)));
      return art;
    } catch (e) {
      setAttachments((xs) => xs.filter((a) => a.key !== key));
      setError(e.message);
      return null;
    }
  };
  const removeAttachment = (key) => setAttachments((xs) => xs.filter((a) => a.key !== key));

  // Paste: images on the clipboard become attachments (text pastes normally).
  const onPaste = (e) => {
    const files = Array.from(e.clipboardData?.items || []).filter((it) => it.kind === 'file' && it.type.startsWith('image/')).map((it) => it.getAsFile()).filter(Boolean);
    if (!files.length) return;
    if (!e.clipboardData.getData('text/plain')) e.preventDefault();
    files.forEach((f, i) => upload(new File([f], f.name && f.name !== 'image.png' ? f.name : `pasted-${Date.now()}-${i}.${(f.type.split('/')[1] || 'png').replace(/[^a-z0-9]/g, '')}`, { type: f.type })));
  };
  const pasteFromPhone = () => {
    setPlusOpen(false);
    const raw = window.LowBotNative?.clipboardImage?.();
    if (!raw) { setError('No image on the clipboard. Copy an image first.'); return; }
    const d = JSON.parse(raw);
    if (d.error) { setError(d.error); return; }
    const bin = atob(d.data_base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i += 1) bytes[i] = bin.charCodeAt(i);
    upload(new File([bytes], d.name, { type: d.mime }));
  };

  // Dictation: browser/OS speech recognition, only after an explicit tap.
  const dictate = () => {
    if (window.LowBotNative?.startDictation) { // Android speech recognition, in the phone's language
      if (listening) { window.LowBotNative.stopDictation?.(); return; } // tap again = done talking
      const base = text.trim();
      const join = (s) => `${base} ${s || ''}`.trim();
      const onEv = (e) => {
        const d = e.detail || {};
        if (d.type === 'partial') setText(join(d.text));
        if (d.type === 'level') setLevel(Number(d.text) || 0);
        if (d.type === 'result') setText(join(d.text));
        if (d.type === 'error') setError(d.text);
        if (d.type === 'result' || d.type === 'error') { setListening(false); setLevel(0); window.removeEventListener('lowbot:dictation', onEv); }
      };
      setError('');
      window.addEventListener('lowbot:dictation', onEv);
      setListening(true);
      window.LowBotNative.startDictation(lang);
      return;
    }
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) { setError('Dictation is not available on this device.'); return; }
    const r = new SR();
    r.lang = 'en-US';
    r.onresult = (e) => setText((x) => `${x} ${e.results[0][0].transcript}`.trim());
    r.onend = () => setListening(false);
    setListening(true);
    r.start();
  };

  // Voice note: recorded locally, uploaded, transcribed by the server STT adapter.
  const toggleVoice = async () => {
    if (recording) { recording.stop(); return; }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const rec = new MediaRecorder(stream);
      const chunks = [];
      rec.ondataavailable = (e) => chunks.push(e.data);
      rec.onstop = async () => {
        stream.getTracks().forEach((tr) => tr.stop());
        setRecording(null);
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' });
        const art = await upload(new File([blob], 'voice-note.webm', { type: blob.type }), 'audio');
        try {
          const tr = await api('/voice/transcribe', { method: 'POST', body: { artifact_id: art.id, language: lang } });
          setText((x) => `${x} ${tr.text}`.trim());
        } catch (err) { setError(`${'Transcription'}: ${err.message}`); }
      };
      rec.start();
      setRecording(rec);
    } catch (err) { setError(`${'Microphone unavailable'}: ${err.message}`); }
  };

  // Tasks currently parked on a secure secret request or a computer takeover: no plain answer box.
  const specialTasks = useMemo(() => {
    const out = new Set();
    messages.forEach((m) => {
      if (m.meta?.secret_request || m.meta?.takeover_request) out.add(m.task_id);
      if (m.meta?.secret_provided) out.delete(m.task_id);
    });
    return out;
  }, [messages]);

  const openComputer = async (target) => {
    if (local) {
      try { await api('/computers/open', { method: 'POST', body: { bot_id: target?.bot_id || target?.id || lead?.id } }); }
      catch (err) { setError(err.message); }
      return;
    }
    onOpenComputer?.(lead);
  };

  const pending = pendingOutbox(conversation.id);
  const title = conversation.title || (conversation.kind === 'group' ? members.map((b) => b.name).join(', ') : lead?.name);
  const placeholder = conversation.kind === 'group'
    ? ('Message the group (@bot)')
    : `${'Ask'} ${lead?.name || ''}`;

  let lastAt = null;
  let dividerShown = false;
  return (
    <div className={cls('relative flex flex-col h-full min-h-0 bg-[#141414]', onBack && 'lb-side-in')}>
      <header className="absolute top-0 inset-x-0 z-10 flex items-center gap-3 px-4 pb-6 bg-gradient-to-b from-[#141414] via-[#141414]/90 to-transparent"
        style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}>
        {onBack && <button aria-label="back" onClick={onBack} className="h-12 w-12 shrink-0 rounded-full bg-[#2a2a2a]/95 border border-white/10 flex items-center justify-center text-2xl"><FiChevronLeft /></button>}
        <button onClick={() => onOpenBot?.(lead, conversation)} className="flex items-center gap-2.5 min-w-0 h-12 rounded-full bg-[#2a2a2a]/95 border border-white/10 pl-3 pr-5">
          {conversation.kind === 'group' ? <BotBlob group size={34} /> : <BotBlob bot={lead} size={34} busy={BUSY.includes(lead?.status)} />}
          <span className="font-semibold text-[17px] truncate">{title}</span>
        </button>
        <span className="flex-1" />
        <button aria-label={t('computer')} title={t('computer')} onClick={() => onOpenComputer?.(conversation.kind === 'group' ? null : lead)}
          className="h-12 w-12 shrink-0 rounded-full bg-[#2a2a2a]/95 border border-white/10 flex items-center justify-center text-xl"><FiMonitor /></button>
      </header>

      <div className="flex-1 overflow-y-auto px-4 pt-20 pb-2 min-h-0">
        {timeline.map((it, i) => {
          const at = new Date(it.at).getTime();
          const sep = lastAt === null || at - lastAt > 10 * 60 * 1000;
          lastAt = at;
          const showDivider = !dividerShown && firstUnread != null && it.kind === 'msg' && it.m.seq > firstUnread && it.m.author_type !== 'user';
          if (showDivider) dividerShown = true;
          const prev = timeline[i - 1];
          return (
            <div key={it.kind === 'msg' ? it.m.id : it.a.id}>
              {showDivider && <div className="lb-rise flex items-center gap-3 my-4"><span className="flex-1 h-px bg-blue-500/50" /><span className="text-[13px] font-semibold tracking-wider text-blue-400">{'NEW'}</span><span className="flex-1 h-px bg-blue-500/50" /></div>}
              {sep && <div className="text-center text-[14px] text-zinc-500 my-4">{dayLabel(it.at, lang)}</div>}
              {it.kind === 'msg'
                ? <Bubble m={it.m} ws={ws} bot={botsById[it.m.author_id]} onOpenComputer={openComputer} animate={i >= timeline.length - 6} showName={conversation.kind === 'group' && it.m.author_type === 'bot'
                    && !(prev?.kind === 'msg' && prev.m.author_id === it.m.author_id && !sep)} />
                : <ApprovalInline a={it.a} ws={ws} onChanged={load} />}
            </div>
          );
        })}
        {elicitations.map((e) => <div key={e.id} className="my-2"><ElicitationCard e={e} ws={ws} /></div>)}
        {pending.map((p) => <div key={p.client_msg_id} className="lb-msg-right flex justify-end my-1.5"><div className="max-w-[85%] rounded-[22px] bg-[#3a3a3c]/60 px-4 py-3 text-[16px] text-zinc-300">⏳ {p.text}</div></div>)}
        {activeTasks.map((task) => <WorkingStrip key={task.id} task={task} bot={botsById[task.bot_id]} special={specialTasks.has(task.id)}
          onAnswer={(id, a) => api(`/tasks/${id}/answer`, { method: 'POST', body: { answer: a } }).then(load)} />)}
        {!timeline.length && lead && (
          <div className="lb-rise flex flex-col items-center text-center mt-16 px-6 text-zinc-400">
            <BotBlob bot={lead} size={88} />
            <div className="mt-3 text-[18px] font-semibold text-zinc-100">{lead.name}</div>
            {lead.role_description && <div className="mt-1 text-[15px]">{lead.role_description}</div>}
          </div>
        )}
        <div ref={endRef} />
      </div>

      <form onSubmit={submit} className="px-4 pt-2 shrink-0 relative" style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}>
        {suggestions.length > 0 && (
          <div className="lb-rise absolute bottom-full left-4 right-4 mb-2 rounded-2xl bg-[#262626] border border-white/10 max-h-56 overflow-y-auto">
            {suggestions.map((s) => (
              <button type="button" key={s.insert} className="block w-full text-left px-4 py-3 text-[15px] hover:bg-white/5"
                onClick={() => setText(text.slice(0, text.length - token.length) + s.insert)}>{s.label}</button>
            ))}
          </div>
        )}
        {plusOpen && (
          <div className="lb-rise lb-stagger absolute bottom-full left-4 mb-2 rounded-2xl bg-[#262626] border border-white/10 overflow-hidden text-[15px] min-w-[220px] shadow-2xl">
            <button type="button" className="flex items-center gap-3 w-full px-4 py-3 hover:bg-white/5" onClick={() => { setPlusOpen(false); fileRef.current.click(); }}><FiPaperclip /> {t('attach')}</button>
            {window.LowBotNative?.clipboardImage && <button type="button" className="flex items-center gap-3 w-full px-4 py-3 hover:bg-white/5" onClick={pasteFromPhone}><FiClipboard /> Paste image</button>}
            <button type="button" className="flex items-center gap-3 w-full px-4 py-3 hover:bg-white/5" onClick={() => { setPlusOpen(false); setText('/'); }}>⚡ {t('skills')}</button>
            <button type="button" className="flex items-center gap-3 w-full px-4 py-3 hover:bg-white/5" onClick={() => { setPlusOpen(false); onOpenComputer?.(lead); }}><FiMonitor /> {t('computer')}</button>
          </div>
        )}
        {attachments.length > 0 && (
          <div className="flex gap-2 overflow-x-auto mb-2 pb-1">
            {attachments.map((a) => (
              <div key={a.key} className="lb-pop relative shrink-0">
                {a.preview ? <img src={a.preview} alt={a.name} className={cls('h-20 w-20 rounded-2xl object-cover bg-[#2a2a2a]', a.uploading && 'opacity-50')} />
                  : <div className={cls('h-20 max-w-[180px] rounded-2xl bg-[#2a2a2a] px-3 flex items-center text-[13px] text-zinc-300', a.uploading && 'opacity-50')}><span className="truncate">📎 {a.name}</span></div>}
                {a.uploading && <span className="absolute inset-0 flex items-center justify-center"><span className="lb-spin h-5 w-5 rounded-full border-2 border-white/30 border-t-white" /></span>}
                <button type="button" aria-label={`Remove ${a.name}`} onClick={() => removeAttachment(a.key)}
                  className="absolute -top-1.5 -right-1.5 h-6 w-6 rounded-full bg-black/80 border border-white/20 text-[12px] flex items-center justify-center">✕</button>
              </div>))}
          </div>)}
        {error && <div className="text-[13px] text-amber-300 mb-2">{error}</div>}
        <input type="file" ref={fileRef} multiple className="hidden" onChange={(e) => { Array.from(e.target.files || []).forEach((f) => upload(f)); e.target.value = ''; }} />
        <div className="flex items-end gap-3">
          <button type="button" aria-label={t('more')} onClick={() => setPlusOpen(!plusOpen)}
            className={cls('h-14 w-14 shrink-0 rounded-full bg-[#2a2a2a] border border-white/10 flex items-center justify-center text-2xl transition', plusOpen && 'rotate-45')}><FiPlus /></button>
          <div className="lb-composer flex-1 min-w-0 flex items-end rounded-[28px] bg-[#2a2a2a] border border-white/10 pl-5 pr-1.5 py-1.5 min-h-[56px]">
            <textarea rows={1} value={text} onChange={(e) => setText(e.target.value)} onPaste={onPaste} placeholder={placeholder} aria-label={t('typeMessage')}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !suggestions.length) submit(e); }}
              className="flex-1 bg-transparent resize-none outline-none text-[17px] text-zinc-100 placeholder:text-zinc-500 max-h-36 py-2.5 min-w-0" />
            {(text.trim() || attachments.length) && !listening ? (
              <button type="submit" aria-label={t('send')} title={t('send')} className="lb-pop lb-press h-11 w-11 rounded-full bg-white text-black flex items-center justify-center shrink-0 text-xl"><FiArrowUp /></button>
            ) : <>
              <button type="button" aria-label={t('dictate')} title={listening ? 'Tap when you are done' : t('dictate')} onClick={dictate}
                className={cls('relative h-11 w-11 flex items-center justify-center shrink-0 text-xl rounded-full transition-colors', listening ? 'text-white bg-rose-600' : 'text-zinc-400')}>
                {listening && <span className="absolute inset-0 rounded-full bg-rose-500/40 transition-transform duration-100" style={{ transform: `scale(${1 + level * 0.06})` }} />}
                <FiMic className="relative" /></button>
              {!local && <button type="button" aria-label={t('voiceNote')} title={t('voiceNote')} onClick={toggleVoice}
                className={cls('h-11 w-14 rounded-full flex items-center justify-center shrink-0', recording ? 'bg-rose-600 text-white' : 'bg-white text-black')}>
                {recording ? <FiSquare /> : <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><g fill="currentColor">{[4, 8, 12, 16, 20].map((x, i) => <rect key={x} x={x - 1} y={[9, 5, 3, 6, 9][i]} width="2" height={[6, 14, 18, 12, 6][i]} rx="1" />)}</g></svg>}
              </button>}
            </>}
          </div>
        </div>
      </form>
    </div>
  );
}
