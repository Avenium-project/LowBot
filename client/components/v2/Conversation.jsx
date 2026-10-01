'use client';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { FiArrowUp, FiCheckCircle, FiChevronLeft, FiClock, FiMic, FiMonitor, FiPaperclip, FiPlus, FiSquare, FiStopCircle, FiXCircle } from 'react-icons/fi';
import { api, downloadPath, pendingOutbox, sendMessage } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { ElicitationCard } from './InboxPanel';
import { BotBlob, cls } from './ui';

const BUSY = ['working', 'queued', 'retrying', 'waiting'];
const RUN_LABEL = { running: 'working', queued: 'queued', waiting_input: 'needs_input', waiting_approval: 'needs_approval',
  waiting_dependency: 'waiting', retry_scheduled: 'retrying', paused: 'paused', unknown_outcome: 'needs_resolution' };

function dayLabel(iso, lang) {
  const d = new Date(iso);
  const now = new Date();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  const time = d.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return `${lang === 'pl' ? 'Dzisiaj' : 'Today'} ${time}`;
  if (d.toDateString() === y.toDateString()) return `${lang === 'pl' ? 'Wczoraj' : 'Yesterday'} ${time}`;
  return d.toLocaleString(lang, { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
}

function Attachment({ a }) {
  const open = () => downloadPath(`/artifacts/${a.artifact_id}/download`, a.name || 'file');
  if (!a.artifact_id) return null;
  return <button onClick={open} className="mt-1 mr-2 inline-flex items-center gap-1 rounded-full bg-black/30 px-3 py-1 text-[13px] text-sky-300">📎 {a.name || 'file'}</button>;
}

function Bubble({ m, bot, showName }) {
  if (m.author_type === 'system') {
    return <div className="text-center text-[13px] text-zinc-500 my-2 px-8 whitespace-pre-wrap">{m.text}</div>;
  }
  const mine = m.author_type === 'user';
  return (
    <div className={cls('flex my-1.5', mine ? 'justify-end' : 'justify-start')}>
      <div className={cls('max-w-[85%] px-4 py-3 text-[16px] leading-snug break-words select-text rounded-[22px]',
        mine ? 'bg-[#3a3a3c] text-white' : 'bg-[#262626] text-zinc-100')}>
        {showName && bot && <div className="flex items-center gap-1.5 mb-1 text-[13px] text-zinc-400"><BotBlob bot={bot} size={18} />{bot.name}</div>}
        {mine ? <div className="whitespace-pre-wrap">{m.text}</div>
          : <div className="prose prose-invert max-w-none prose-p:my-1 prose-pre:my-2 text-[16px]"><ReactMarkdown>{m.text}</ReactMarkdown></div>}
        {m.attachments?.length > 0 && <div>{m.attachments.map((a, i) => <Attachment key={i} a={a} />)}</div>}
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
    approved: [lang === 'pl' ? 'Zatwierdzono' : 'Approved', 'bg-emerald-900/40 text-emerald-400', FiCheckCircle],
    consumed: [lang === 'pl' ? 'Zatwierdzono' : 'Approved', 'bg-emerald-900/40 text-emerald-400', FiCheckCircle],
    denied: [lang === 'pl' ? 'Odrzucono' : 'Denied', 'bg-rose-900/40 text-rose-400', FiXCircle],
    expired: [lang === 'pl' ? 'Wygasło' : 'Expired', 'bg-zinc-800 text-zinc-400', FiClock],
  }[a.status];
  const Icon = done?.[2];
  const description = `${a.summary}${a.effect ? ` — ${a.effect}` : ''}${a.target ? ` (${a.target})` : ''}`;
  return (
    <div className="my-2 rounded-[22px] bg-[#262626] px-4 py-4">
      <div className="text-[17px] font-semibold text-white">{lang === 'pl' ? 'Prośba o zgodę' : 'Approval request'}</div>
      <button onClick={() => setOpen(!open)} className={cls('text-left text-[15px] text-zinc-200 mt-1', !open && 'line-clamp-3')}>{description}</button>
      {open && !editing && <pre className="mt-2 text-xs bg-black/30 rounded-xl p-3 whitespace-pre-wrap break-all max-h-48 overflow-y-auto text-zinc-300">{JSON.stringify(a.display, null, 2)}</pre>}
      {editing && <textarea className="mt-2 w-full rounded-xl bg-black/40 p-3 font-mono text-xs h-32 text-zinc-100 outline-none" value={draft} onChange={(e) => setDraft(e.target.value)} />}
      {err && <div className="text-xs text-rose-300 mt-2">{err}</div>}
      {done ? (
        <div className={cls('mt-3 flex items-center justify-center gap-2 rounded-xl py-2.5 text-[15px] font-medium', done[1])}><Icon /> {done[0]}</div>
      ) : editing ? (
        <div className="mt-3 grid grid-cols-2 gap-2">
          <button onClick={saveEdit} className="rounded-xl bg-white text-black py-2.5 font-medium">{t('save')}</button>
          <button onClick={() => setEditing(false)} className="rounded-xl bg-[#3a3a3c] py-2.5">{t('cancel')}</button>
        </div>
      ) : (
        <div className="mt-3 grid grid-cols-3 gap-2">
          <button onClick={() => decide('approve')} className="rounded-xl bg-white text-black py-2.5 font-medium">{t('approve')}</button>
          <button onClick={() => decide('deny')} className="rounded-xl bg-[#3a3a3c] py-2.5">{t('deny')}</button>
          <button onClick={() => { setOpen(true); setEditing(true); }} className="rounded-xl bg-[#3a3a3c] py-2.5">{t('edit')}</button>
        </div>
      )}
    </div>
  );
}

function WorkingStrip({ task, bot, onAnswer }) {
  const { t } = useT();
  const [answer, setAnswer] = useState('');
  const asking = task.run_status === 'waiting_input';
  return (
    <div className="my-2 rounded-[18px] bg-[#1f1f1f] border border-white/5 px-4 py-2.5 text-[14px]">
      <div className="flex items-center gap-2">
        <span className={cls('h-2 w-2 rounded-full', asking ? 'bg-amber-400' : 'bg-emerald-400 animate-pulse')} />
        <span className="flex-1 truncate text-zinc-300">{bot?.name}: {t(`status_${RUN_LABEL[task.run_status] || 'working'}`)}</span>
        <button aria-label={t('stop')} title={t('stop')} onClick={() => api(`/tasks/${task.id}/cancel`, { method: 'POST' })}
          className="text-zinc-400 hover:text-rose-300 h-8 w-8 flex items-center justify-center"><FiStopCircle /></button>
      </div>
      {asking && (
        <form className="flex gap-2 mt-2" onSubmit={(e) => { e.preventDefault(); if (answer.trim()) { onAnswer(task.id, answer); setAnswer(''); } }}>
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
  const [plusOpen, setPlusOpen] = useState(false);
  const [firstUnread] = useState(conversation.unread > 0 ? conversation.last_read_seq : null);
  const endRef = useRef(null);
  const fileRef = useRef(null);
  const botsById = useMemo(() => Object.fromEntries(ws.bots.map((b) => [b.id, b])), [ws.bots]);
  const members = conversation.bot_ids.map((id) => botsById[id]).filter(Boolean);
  const lead = members[0];
  const activeTasks = ws.tasks.filter((x) => x.conversation_id === conversation.id && !['completed', 'failed', 'cancelled'].includes(x.status));
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
    setError('');
    const body = text; const att = attachments;
    setText(''); setAttachments([]);
    try { await sendMessage(conversation.id, body, att); }
    catch (err) { setError(err.status ? err.message : (lang === 'pl' ? 'Brak sieci — wyślę, gdy wróci połączenie.' : 'Offline — will send when back online.')); }
    load();
  };

  const upload = async (file, kind) => {
    const form = new FormData();
    form.append('file', file);
    const art = await api(`/artifacts/upload?conversation_id=${conversation.id}`, { method: 'POST', form });
    setAttachments((xs) => [...xs, { artifact_id: art.id, name: art.name, kind: kind || (file.type.startsWith('image/') ? 'image' : 'file') }]);
    return art;
  };

  // Dictation: browser/OS speech recognition, only after an explicit tap.
  const dictate = () => {
    if (window.LowBotNative?.startDictation) { // Android shell: native speech recognizer
      const onEv = (e) => {
        const d = e.detail || {};
        if (d.type === 'result') setText((x) => `${x} ${d.text}`.trim());
        if (d.type === 'error') setError(d.text);
        if (d.type !== 'listening') { setListening(false); window.removeEventListener('lowbot:dictation', onEv); }
      };
      window.addEventListener('lowbot:dictation', onEv);
      setListening(true);
      window.LowBotNative.startDictation(lang);
      return;
    }
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) { setError(lang === 'pl' ? 'Dyktowanie nie jest dostępne na tym urządzeniu.' : 'Dictation is not available on this device.'); return; }
    const r = new SR();
    r.lang = lang === 'pl' ? 'pl-PL' : 'en-US';
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
        } catch (err) { setError(`${lang === 'pl' ? 'Transkrypcja' : 'Transcription'}: ${err.message}`); }
      };
      rec.start();
      setRecording(rec);
    } catch (err) { setError(`${lang === 'pl' ? 'Brak dostępu do mikrofonu' : 'Microphone unavailable'}: ${err.message}`); }
  };

  const pending = pendingOutbox(conversation.id);
  const title = conversation.title || (conversation.kind === 'group' ? members.map((b) => b.name).join(', ') : lead?.name);
  const placeholder = conversation.kind === 'group'
    ? (lang === 'pl' ? 'Napisz do grupy (@bot)' : 'Message the group (@bot)')
    : `${lang === 'pl' ? 'Zapytaj' : 'Ask'} ${lead?.name || ''}`;

  let lastAt = null;
  let dividerShown = false;
  return (
    <div className="relative flex flex-col h-full min-h-0 bg-[#141414]">
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
              {showDivider && <div className="flex items-center gap-3 my-4"><span className="flex-1 h-px bg-blue-500/50" /><span className="text-[13px] font-semibold tracking-wider text-blue-400">{lang === 'pl' ? 'NOWE' : 'NEW'}</span><span className="flex-1 h-px bg-blue-500/50" /></div>}
              {sep && <div className="text-center text-[14px] text-zinc-500 my-4">{dayLabel(it.at, lang)}</div>}
              {it.kind === 'msg'
                ? <Bubble m={it.m} bot={botsById[it.m.author_id]} showName={conversation.kind === 'group' && it.m.author_type === 'bot'
                    && !(prev?.kind === 'msg' && prev.m.author_id === it.m.author_id && !sep)} />
                : <ApprovalInline a={it.a} ws={ws} onChanged={load} />}
            </div>
          );
        })}
        {elicitations.map((e) => <div key={e.id} className="my-2"><ElicitationCard e={e} ws={ws} /></div>)}
        {pending.map((p) => <div key={p.client_msg_id} className="flex justify-end my-1.5"><div className="max-w-[85%] rounded-[22px] bg-[#3a3a3c]/60 px-4 py-3 text-[16px] text-zinc-300">⏳ {p.text}</div></div>)}
        {activeTasks.map((task) => <WorkingStrip key={task.id} task={task} bot={botsById[task.bot_id]}
          onAnswer={(id, a) => api(`/tasks/${id}/answer`, { method: 'POST', body: { answer: a } }).then(load)} />)}
        {!timeline.length && lead && (
          <div className="flex flex-col items-center text-center mt-16 px-6 text-zinc-400">
            <BotBlob bot={lead} size={88} />
            <div className="mt-3 text-[18px] font-semibold text-zinc-100">{lead.name}</div>
            {lead.role_description && <div className="mt-1 text-[15px]">{lead.role_description}</div>}
          </div>
        )}
        <div ref={endRef} />
      </div>

      <form onSubmit={submit} className="px-4 pt-2 shrink-0 relative" style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}>
        {suggestions.length > 0 && (
          <div className="absolute bottom-full left-4 right-4 mb-2 rounded-2xl bg-[#262626] border border-white/10 max-h-56 overflow-y-auto">
            {suggestions.map((s) => (
              <button type="button" key={s.insert} className="block w-full text-left px-4 py-3 text-[15px] hover:bg-white/5"
                onClick={() => setText(text.slice(0, text.length - token.length) + s.insert)}>{s.label}</button>
            ))}
          </div>
        )}
        {plusOpen && (
          <div className="absolute bottom-full left-4 mb-2 rounded-2xl bg-[#262626] border border-white/10 overflow-hidden text-[15px] min-w-[220px]">
            <button type="button" className="flex items-center gap-3 w-full px-4 py-3 hover:bg-white/5" onClick={() => { setPlusOpen(false); fileRef.current.click(); }}><FiPaperclip /> {t('attach')}</button>
            <button type="button" className="flex items-center gap-3 w-full px-4 py-3 hover:bg-white/5" onClick={() => { setPlusOpen(false); setText('/'); }}>⚡ {t('skills')}</button>
            <button type="button" className="flex items-center gap-3 w-full px-4 py-3 hover:bg-white/5" onClick={() => { setPlusOpen(false); onOpenComputer?.(lead); }}><FiMonitor /> {t('computer')}</button>
          </div>
        )}
        {attachments.length > 0 && <div className="text-[13px] text-zinc-400 mb-2">{attachments.map((a) => `📎 ${a.name}`).join('  ')}</div>}
        {error && <div className="text-[13px] text-amber-300 mb-2">{error}</div>}
        <input type="file" ref={fileRef} className="hidden" onChange={(e) => e.target.files[0] && upload(e.target.files[0])} />
        <div className="flex items-end gap-3">
          <button type="button" aria-label={t('more')} onClick={() => setPlusOpen(!plusOpen)}
            className={cls('h-14 w-14 shrink-0 rounded-full bg-[#2a2a2a] border border-white/10 flex items-center justify-center text-2xl transition', plusOpen && 'rotate-45')}><FiPlus /></button>
          <div className="flex-1 min-w-0 flex items-end rounded-[28px] bg-[#2a2a2a] border border-white/10 pl-5 pr-1.5 py-1.5 min-h-[56px]">
            <textarea rows={1} value={text} onChange={(e) => setText(e.target.value)} placeholder={placeholder} aria-label={t('typeMessage')}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !suggestions.length) submit(e); }}
              className="flex-1 bg-transparent resize-none outline-none text-[17px] text-zinc-100 placeholder:text-zinc-500 max-h-36 py-2.5 min-w-0" />
            {text.trim() || attachments.length ? (
              <button type="submit" aria-label={t('send')} title={t('send')} className="h-11 w-11 rounded-full bg-white text-black flex items-center justify-center shrink-0 text-xl"><FiArrowUp /></button>
            ) : <>
              <button type="button" aria-label={t('dictate')} title={t('dictate')} onClick={dictate} className={cls('h-11 w-10 flex items-center justify-center shrink-0 text-xl', listening ? 'text-rose-400' : 'text-zinc-400')}><FiMic /></button>
              <button type="button" aria-label={t('voiceNote')} title={t('voiceNote')} onClick={toggleVoice}
                className={cls('h-11 w-14 rounded-full flex items-center justify-center shrink-0', recording ? 'bg-rose-600 text-white' : 'bg-white text-black')}>
                {recording ? <FiSquare /> : <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><g fill="currentColor">{[4, 8, 12, 16, 20].map((x, i) => <rect key={x} x={x - 1} y={[9, 5, 3, 6, 9][i]} width="2" height={[6, 14, 18, 12, 6][i]} rx="1" />)}</g></svg>}
              </button>
            </>}
          </div>
        </div>
      </form>
    </div>
  );
}
