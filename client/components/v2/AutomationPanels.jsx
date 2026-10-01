'use client';
import { useEffect, useState } from 'react';
import { api, isLocal, saveBlob } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { BotBlob, Button, Card, Empty, Field, Section, TaskBadge, Toggle, botLabel, fmtTime, inputCls } from './ui';

export function RoutinesPanel({ ws }) {
  const { t } = useT();
  const [list, setList] = useState([]);
  const [form, setForm] = useState({ bot_id: '', name: '', schedule: 'every day at 8:00', prompt: '', kind: 'schedule' });
  const [preview, setPreview] = useState(null);
  const [msg, setMsg] = useState('');
  const [hist, setHist] = useState({});
  const [open, setOpen] = useState(false);
  const load = () => api('/routines').then(setList);
  useEffect(() => { load(); }, [ws.tick]);
  useEffect(() => {
    if (form.kind !== 'schedule' || !form.schedule) return;
    const h = setTimeout(() => api('/routines/parse', { method: 'POST', body: { text: form.schedule } })
      .then(setPreview).catch((e) => setPreview({ error: e.message })), 300);
    return () => clearTimeout(h);
  }, [form.schedule, form.kind]);
  const create = async () => {
    setMsg('');
    try {
      const r = await api('/routines', { method: 'POST', body: {
        bot_id: form.bot_id || ws.bots[0]?.id, name: form.name || 'Routine', prompt: form.prompt,
        schedule: form.kind === 'schedule' ? form.schedule : undefined, kind: form.kind === 'event' ? 'event' : undefined,
      } });
      if (r.webhook_secret) setMsg(`Webhook: POST ${r.webhook_path} — secret (shown once): ${r.webhook_secret}`);
      load(); setOpen(false);
    } catch (e) { setMsg(e.message); }
  };
  const bot = (id) => ws.bots.find((b) => b.id === id);
  const local = isLocal();
  const form_ = open && (
    <Card className="space-y-3 mb-4">
      <Field label="Bot"><select className={inputCls} value={form.bot_id} onChange={(e) => setForm({ ...form, bot_id: e.target.value })}>
        {ws.bots.map((b) => <option key={b.id} value={b.id}>{botLabel(b)}</option>)}</select></Field>
      <Field label={t('name')}><input className={inputCls} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
      {!local && <Field label="Trigger"><select className={inputCls} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
        <option value="schedule">{t('schedule')}</option><option value="event">Webhook</option></select></Field>}
      {form.kind === 'schedule' && <Field label={t('schedule')} hint="e.g. “every day at 8:00”, “weekdays at 7:30”, “every 15 minutes”, or cron">
        <input className={inputCls} value={form.schedule} onChange={(e) => setForm({ ...form, schedule: e.target.value })} /></Field>}
      {preview && !preview.error && <div className="text-[13px] text-zinc-500 px-1">{preview.schedule.kind === 'cron' ? `cron: ${preview.schedule.cron}` : 'once'} · {preview.timezone}<br />{t('nextRuns')}: {preview.next_runs_local.slice(0, 3).join(' · ')}</div>}
      {preview?.error && <div className="text-[13px] text-rose-400 px-1">{preview.error}</div>}
      <Field label={t('prompt')}><textarea className={`${inputCls} h-24`} value={form.prompt} onChange={(e) => setForm({ ...form, prompt: e.target.value })} /></Field>
      <div className="flex gap-2"><Button kind="primary" onClick={create}>{t('create')}</Button><Button onClick={() => setOpen(false)}>{t('cancel')}</Button></div>
    </Card>
  );
  return (
    <div>
      <Section title={t('routines')} actions={!open && <Button small kind="primary" onClick={() => setOpen(true)}>+ New routine</Button>}>
        {form_}
        {msg && <div className="text-[13px] text-amber-400 break-all select-text mb-3 px-1 whitespace-pre-wrap">{msg}</div>}
        {list.length ? <div className="space-y-3">{list.map((r) => (
          <Card key={r.id}>
            <div className="flex items-start gap-3">
              <BotBlob bot={bot(r.bot_id)} size={40} />
              <div className="flex-1 min-w-0">
                <div className="text-[17px] font-medium truncate">{r.name}</div>
                <div className="text-[13px] text-zinc-500">{bot(r.bot_id)?.name} · {r.kind === 'event' ? 'webhook' : (r.schedule.source || r.schedule.cron || r.schedule.at)}</div>
                {r.enabled && r.preview?.length > 0 && <div className="text-[13px] text-zinc-500">Next: {r.preview[0]}</div>}
              </div>
              <Toggle on={r.enabled} label={r.name} onChange={(on) => api(`/routines/${r.id}`, { method: 'PATCH', body: { enabled: on } }).then(load)} />
            </div>
            <div className="flex gap-2 flex-wrap mt-3">
              <Button small onClick={() => api(`/routines/${r.id}/test-run`, { method: 'POST' }).then(load)}>{t('testRun')}</Button>
              <Button small onClick={() => api(`/routines/${r.id}/simulate`, { method: 'POST' }).then((x) => setMsg(`${x.prompt}\n${t('nextRuns')}: ${x.next_runs_local.join(' · ')}`))}>{t('simulate')}</Button>
              <Button small onClick={() => (hist[r.id] ? setHist({ ...hist, [r.id]: null }) : api(`/routines/${r.id}/history`).then((h) => setHist({ ...hist, [r.id]: h })))}>{t('history')}</Button>
              <Button small kind="danger" onClick={() => confirm('Delete?') && api(`/routines/${r.id}`, { method: 'DELETE' }).then(load)}>{t('delete')}</Button>
            </div>
            {hist[r.id] && <div className="lb-rise mt-3 space-y-2">{hist[r.id].length ? hist[r.id].map((h) => (
              <div key={h.id} className="flex items-center gap-2 text-[13px]"><TaskBadge status={h.task_status || h.status} />
                <span className="text-zinc-400">{fmtTime(h.scheduled_for || h.created_at)} · {h.trigger}</span>
                <span className="text-zinc-500 truncate flex-1">{h.result_text || h.task_error || h.error}</span></div>))
              : <div className="text-[13px] text-zinc-500">No runs yet</div>}</div>}
          </Card>
        ))}</div> : !open && <Empty>No routines yet — tap “New routine”.</Empty>}
      </Section>
    </div>
  );
}

export function MemoryPanel({ ws }) {
  const { t } = useT();
  const [q, setQ] = useState('');
  const [rows, setRows] = useState([]);
  const [draft, setDraft] = useState({ content: '', scope: 'team', bot_id: '' });
  const load = () => api(`/memories${q ? `?q=${encodeURIComponent(q)}` : ''}`).then(setRows);
  useEffect(() => { load(); }, [q, ws.tick]);
  return (
    <Section title={t('memory')}>
      <input className={`${inputCls} mb-2`} placeholder={t('search')} value={q} onChange={(e) => setQ(e.target.value)} />
      <Card className="mb-2 space-y-2">
        <textarea className={inputCls} placeholder="Fact / note" value={draft.content} onChange={(e) => setDraft({ ...draft, content: e.target.value })} />
        <div className="flex gap-2 flex-wrap">
          <select className={inputCls} value={draft.scope} onChange={(e) => setDraft({ ...draft, scope: e.target.value })}><option value="team">team</option><option value="bot">bot</option></select>
          {draft.scope === 'bot' && <select className={inputCls} value={draft.bot_id} onChange={(e) => setDraft({ ...draft, bot_id: e.target.value })}>
            <option value="">—</option>{ws.bots.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select>}
          <Button kind="primary" onClick={() => api('/memories', { method: 'POST', body: draft }).then(() => { setDraft({ ...draft, content: '' }); load(); })}>{t('save')}</Button>
        </div>
      </Card>
      {rows.length ? rows.map((m) => (
        <Card key={m.id} className="mb-2">
          <div className="text-[13px] text-zinc-500">{m.scope}{m.bot_id ? ` · ${ws.bots.find((b) => b.id === m.bot_id)?.name || m.bot_id}` : ''} · {m.source} · {fmtTime(m.updated_at)}</div>
          <div className="text-[15px] whitespace-pre-wrap select-text">{m.content}</div>
          <div className="flex gap-2 mt-1">
            <Button small onClick={() => { const c = prompt('Edit', m.content); if (c != null) api(`/memories/${m.id}`, { method: 'PATCH', body: { content: c } }).then(load); }}>{t('edit')}</Button>
            <Button small kind="danger" onClick={() => api(`/memories/${m.id}`, { method: 'DELETE' }).then(load)}>{t('delete')}</Button>
          </div>
        </Card>
      )) : <Empty />}
    </Section>
  );
}

export function SkillsPanel({ ws, skills, reloadSkills }) {
  const { t } = useT();
  const [form, setForm] = useState({ name: '', instructions: '', tools: '', completion_criteria: '' });
  const [teach, setTeach] = useState({ run_id: '', name: '', consent: false });
  const [msg, setMsg] = useState('');
  const save = async () => {
    try {
      await api('/skills', { method: 'POST', body: { ...form, tools: form.tools.split(',').map((x) => x.trim()).filter(Boolean) } });
      setForm({ name: '', instructions: '', tools: '', completion_criteria: '' }); reloadSkills();
    } catch (e) { setMsg(e.message); }
  };
  return (
    <div>
      <Section title={t('skills')}>
        <Card className="space-y-2 mb-3">
          <Field label={t('name')}><input className={inputCls} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label={t('instructions')}><textarea className={`${inputCls} h-24`} value={form.instructions} onChange={(e) => setForm({ ...form, instructions: e.target.value })} /></Field>
          <Field label={t('tools')} hint="np. web.fetch, workspace.*"><input className={inputCls} value={form.tools} onChange={(e) => setForm({ ...form, tools: e.target.value })} /></Field>
          <Field label="Done when"><input className={inputCls} value={form.completion_criteria} onChange={(e) => setForm({ ...form, completion_criteria: e.target.value })} /></Field>
          <Button kind="primary" onClick={save}>{t('create')}</Button>
        </Card>
        {skills.map((s) => (
          <Card key={s.id} className="mb-2">
            <div className="flex justify-between flex-wrap gap-2"><div><b>/{s.slug}</b> — {s.name} · v{s.version} · {s.source}</div>
              <div className="flex gap-1">
                <Button small onClick={() => api(`/skills/${s.id}/export`).then((x) => saveBlob(new Blob([JSON.stringify(x, null, 2)], { type: 'application/json' }), `${s.slug}.skill.json`))}>{t('export')}</Button>
                <Button small onClick={() => { const v = prompt('Instructions', s.instructions); if (v != null) api(`/skills/${s.id}`, { method: 'PATCH', body: { instructions: v } }).then(reloadSkills); }}>{t('edit')}</Button>
                <Button small kind="danger" onClick={() => api(`/skills/${s.id}`, { method: 'DELETE' }).then(reloadSkills)}>{t('delete')}</Button>
              </div></div>
            <pre className="text-[13px] text-zinc-400 whitespace-pre-wrap max-h-32 overflow-y-auto">{s.instructions}</pre>
          </Card>
        ))}
      </Section>
      <Section title="Teach a task">
        <Card className="space-y-2">
          <div className="text-[13px] text-zinc-400">Turns the tool steps of a finished run into an editable DRAFT skill. Sensitive values are redacted and typed text becomes inputs. Test the draft on safe data.</div>
          <input className={inputCls} placeholder="run id (from task details)" value={teach.run_id} onChange={(e) => setTeach({ ...teach, run_id: e.target.value })} />
          <input className={inputCls} placeholder={t('name')} value={teach.name} onChange={(e) => setTeach({ ...teach, name: e.target.value })} />
          <label className="text-[15px] flex gap-2 items-center"><input type="checkbox" checked={teach.consent} onChange={(e) => setTeach({ ...teach, consent: e.target.checked })} /> I consent to using this recording</label>
          <Button disabled={!teach.consent} onClick={() => api('/skills/teach', { method: 'POST', body: teach }).then(reloadSkills).catch((e) => setMsg(e.message))}>{t('create')}</Button>
        </Card>
        {msg && <div className="text-[13px] text-rose-400">{msg}</div>}
      </Section>
    </div>
  );
}
