'use client';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { FiMic, FiPaperclip, FiSend, FiSquare, FiStopCircle, FiArrowLeft, FiUsers } from 'react-icons/fi';
import { api, fetchBlobUrl, pendingOutbox, sendMessage } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, StatusDot, TaskBadge, cls, fmtTime, inputCls } from './ui';

function Attachment({ a }) {
  const [url, setUrl] = useState(null);
  const open = async () => {
    const u = url || await fetchBlobUrl(`/artifacts/${a.artifact_id}/download`);
    setUrl(u);
    const link = document.createElement('a');
    link.href = u; link.download = a.name || 'file'; link.click();
  };
  if (!a.artifact_id) return null;
  return <button onClick={open} className="text-xs underline text-sky-300 mr-2">📎 {a.name || a.artifact_id}</button>;
}

function Message({ m, botsById }) {
  const bot = m.author_type === 'bot' ? botsById[m.author_id] : null;
  if (m.author_type === 'system') {
    return <div className="text-center text-xs text-zinc-500 my-2 px-6 whitespace-pre-wrap">{m.text}</div>;
  }
  const mine = m.author_type === 'user';
  return (
    <div className={cls('flex my-2 gap-2', mine ? 'justify-end' : 'justify-start')}>
      {!mine && <div className="h-8 w-8 shrink-0 rounded-full bg-zinc-800 flex items-center justify-center">{bot?.avatar || '🤖'}</div>}
      <div className={cls('max-w-[85%] md:max-w-[70%] rounded-2xl px-3 py-2 text-sm', mine ? 'bg-blue-600 text-white' : 'bg-zinc-800 text-zinc-100')}>
        {!mine && <div className="text-[11px] text-zinc-400 mb-0.5">{bot?.name || m.author_id} · {fmtTime(m.created_at)}</div>}
        <div className="prose prose-invert prose-sm max-w-none break-words select-text"><ReactMarkdown>{m.text}</ReactMarkdown></div>
        {m.attachments?.length > 0 && <div className="mt-1">{m.attachments.map((a, i) => <Attachment key={i} a={a} />)}</div>}
      </div>
    </div>
  );
}

function ActiveTask({ task, bot, onAnswer }) {
  const { t } = useT();
  const [answer, setAnswer] = useState('');
  const waitingInput = task.run_status === 'waiting_input';
  return (
    <div className="rounded-lg border border-white/10 bg-zinc-900 px-3 py-2 text-xs flex flex-col gap-1">
      <div className="flex items-center gap-2 justify-between">
        <span className="truncate">{bot?.avatar} <b>{bot?.name}</b>: {task.title}</span>
        <span className="flex items-center gap-2 shrink-0">
          <TaskBadge status={task.run_status || task.status} />
          <button aria-label={t('stop')} title={t('stop')} onClick={() => api(`/tasks/${task.id}/cancel`, { method: 'POST' })}
            className="text-rose-300 hover:text-rose-200 min-h-[32px] min-w-[32px] flex items-center justify-center"><FiStopCircle /></button>
        </span>
      </div>
      {waitingInput && (
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (answer.trim()) { onAnswer(task.id, answer); setAnswer(''); } }}>
          <input className={inputCls} value={answer} onChange={(e) => setAnswer(e.target.value)} placeholder={t('answer')} />
          <Button small kind="primary" type="submit">{t('answer')}</Button>
        </form>
      )}
    </div>
  );
}

export default function Conversation({ conversation, ws, onBack, skills }) {
  const { t, lang } = useT();
  const [messages, setMessages] = useState([]);
  const [text, setText] = useState('');
  const [attachments, setAttachments] = useState([]);
  const [error, setError] = useState('');
  const [recording, setRecording] = useState(null);
  const [listening, setListening] = useState(false);
  const endRef = useRef(null);
  const fileRef = useRef(null);
  const botsById = useMemo(() => Object.fromEntries(ws.bots.map((b) => [b.id, b])), [ws.bots]);
  const members = conversation.bot_ids.map((id) => botsById[id]).filter(Boolean);
  const activeTasks = ws.tasks.filter((x) => x.conversation_id === conversation.id
    && !['completed', 'failed', 'cancelled'].includes(x.status));

  const load = useCallback(async () => {
    const rows = await api(`/conversations/${conversation.id}/messages?limit=500`);
    setMessages(rows);
    if (rows.length) api(`/conversations/${conversation.id}/read`, { method: 'POST', body: { seq: rows[rows.length - 1].seq } }).catch(() => {});
  }, [conversation.id]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => ws.subscribe((ev) => { if (ev.conversation_id === conversation.id && ev.type === 'message.created') load(); }), [ws, conversation.id, load]);
  useEffect(() => { endRef.current?.scrollIntoView({ block: 'end' }); }, [messages.length]);

  // @mention and /skill suggestions
  const token = text.split(/\s/).pop() || '';
  const suggestions = token.startsWith('@')
    ? members.filter((b) => b.handle.startsWith(token.slice(1).toLowerCase())).map((b) => ({ insert: `@${b.handle} `, label: `${b.avatar} @${b.handle}` }))
    : (token.startsWith('/') && text.trim() === token)
      ? skills.filter((s) => s.slug.startsWith(token.slice(1))).map((s) => ({ insert: `/${s.slug} `, label: `/${s.slug} — ${s.name}` }))
      : [];

  const submit = async (e) => {
    e?.preventDefault();
    if (!text.trim() && !attachments.length) return;
    setError('');
    const body = text;
    const att = attachments;
    setText(''); setAttachments([]);
    try {
      await sendMessage(conversation.id, body, att);
    } catch (err) {
      setError(err.status ? err.message : `${err.message} — ${lang === 'pl' ? 'zostanie wysłane po odzyskaniu sieci' : 'will be sent when back online'}`);
    }
    load();
  };

  const upload = async (file, kind) => {
    const form = new FormData();
    form.append('file', file);
    const art = await api(`/artifacts/upload?conversation_id=${conversation.id}`, { method: 'POST', form });
    setAttachments((xs) => [...xs, { artifact_id: art.id, name: art.name, kind: kind || (file.type.startsWith('image/') ? 'image' : 'file') }]);
    return art;
  };

  // Dictation: browser speech recognition, only after an explicit click.
  const dictate = () => {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) { setError(lang === 'pl' ? 'Dyktowanie nie jest dostępne w tej przeglądarce.' : 'Dictation is not available in this browser.'); return; }
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
        } catch (err) { setError(`STT: ${err.message}`); }
      };
      rec.start();
      setRecording(rec);
    } catch (err) { setError(`${lang === 'pl' ? 'Brak dostępu do mikrofonu' : 'Microphone unavailable'}: ${err.message}`); }
  };

  const pending = pendingOutbox(conversation.id);
  const title = conversation.title || members.map((b) => b.name).join(', ');

  return (
    <div className="flex flex-col h-full min-h-0">
      <header className="flex items-center gap-2 px-3 py-2 border-b border-white/10 shrink-0">
        {onBack && <button aria-label="back" onClick={onBack} className="md:hidden min-h-[40px] min-w-[40px] flex items-center justify-center"><FiArrowLeft /></button>}
        <div className="min-w-0 flex-1">
          <div className="font-semibold truncate flex items-center gap-2">{conversation.kind === 'group' && <FiUsers />}{title}</div>
          <div className="flex gap-3 flex-wrap">{members.map((b) => <span key={b.id} className="text-xs text-zinc-400">{b.avatar} {b.name} <StatusDot status={b.status} /></span>)}</div>
        </div>
      </header>
      <div className="flex-1 overflow-y-auto px-3 py-2 min-h-0">
        {messages.map((m) => <Message key={m.id} m={m} botsById={botsById} />)}
        {pending.map((p) => <div key={p.client_msg_id} className="text-right text-xs text-zinc-500">⏳ {p.text}</div>)}
        <div ref={endRef} />
      </div>
      {activeTasks.length > 0 && (
        <div className="px-3 py-2 space-y-1 border-t border-white/5 max-h-40 overflow-y-auto shrink-0">
          {activeTasks.map((task) => <ActiveTask key={task.id} task={task} bot={botsById[task.bot_id]}
            onAnswer={(id, a) => api(`/tasks/${id}/answer`, { method: 'POST', body: { answer: a } }).then(load)} />)}
        </div>
      )}
      <form onSubmit={submit} className="p-2 border-t border-white/10 shrink-0 relative" style={{ paddingBottom: 'max(0.5rem, env(safe-area-inset-bottom))' }}>
        {suggestions.length > 0 && (
          <div className="absolute bottom-full left-2 right-2 mb-1 rounded-lg bg-zinc-900 border border-white/10 max-h-48 overflow-y-auto">
            {suggestions.map((s) => (
              <button type="button" key={s.insert} className="block w-full text-left px-3 py-2 text-sm hover:bg-white/5"
                onClick={() => setText(text.slice(0, text.length - token.length) + s.insert)}>{s.label}</button>
            ))}
          </div>
        )}
        {attachments.length > 0 && <div className="text-xs text-zinc-400 mb-1">{attachments.map((a) => `📎 ${a.name}`).join('  ')}</div>}
        {error && <div className="text-xs text-rose-300 mb-1">{error}</div>}
        <div className="flex items-end gap-1">
          <input type="file" ref={fileRef} className="hidden" onChange={(e) => e.target.files[0] && upload(e.target.files[0])} />
          <button type="button" aria-label={t('attach')} title={t('attach')} onClick={() => fileRef.current.click()} className="min-h-[40px] min-w-[40px] flex items-center justify-center text-zinc-400"><FiPaperclip /></button>
          <button type="button" aria-label={t('dictate')} title={t('dictate')} onClick={dictate} className={cls('min-h-[40px] min-w-[40px] flex items-center justify-center', listening ? 'text-rose-400' : 'text-zinc-400')}><FiMic /></button>
          <button type="button" aria-label={t('voiceNote')} title={t('voiceNote')} onClick={toggleVoice} className={cls('min-h-[40px] min-w-[40px] flex items-center justify-center', recording ? 'text-rose-400 animate-pulse' : 'text-zinc-400')}>{recording ? <FiSquare /> : '🎙'}</button>
          <textarea rows={1} value={text} onChange={(e) => setText(e.target.value)} placeholder={t('typeMessage')}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !suggestions.length) submit(e); }}
            className={cls(inputCls, 'resize-none max-h-32 min-h-[40px]')} />
          <Button type="submit" kind="primary" title={t('send')}><FiSend /></Button>
        </div>
      </form>
    </div>
  );
}
