'use client';
import { useEffect, useState } from 'react';
import { api, saveBlob } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Empty, Field, Section, TaskBadge, fmtTime, inputCls } from './ui';

export function RoutinesPanel({ ws }) {
  const { t } = useT();
  const [list, setList] = useState([]);
  const [form, setForm] = useState({ bot_id: '', name: '', schedule: 'codziennie o 8:00', prompt: '', kind: 'schedule' });
  const [preview, setPreview] = useState(null);
  const [msg, setMsg] = useState('');
  const [hist, setHist] = useState({});
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
      load();
    } catch (e) { setMsg(e.message); }
  };
  const bot = (id) => ws.bots.find((b) => b.id === id);
  return (
    <div>
      <Section title={t('routines')}>
        <Card className="space-y-2 mb-3">
          <Field label="Bot"><select className={inputCls} value={form.bot_id} onChange={(e) => setForm({ ...form, bot_id: e.target.value })}>
            {ws.bots.map((b) => <option key={b.id} value={b.id}>{b.avatar} {b.name}</option>)}</select></Field>
          <Field label={t('name')}><input className={inputCls} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label="Trigger"><select className={inputCls} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
            <option value="schedule">{t('schedule')}</option><option value="event">Webhook</option></select></Field>
          {form.kind === 'schedule' && <Field label={t('schedule')} hint="np. „codziennie o 8:00”, „w dni robocze o 7:30”, „co 15 minut”, cron">
            <input className={inputCls} value={form.schedule} onChange={(e) => setForm({ ...form, schedule: e.target.value })} /></Field>}
          {preview && !preview.error && <div className="text-xs text-zinc-400">{preview.schedule.kind === 'cron' ? `cron: ${preview.schedule.cron}` : 'once'} · {preview.timezone}<br />{t('nextRuns')}: {preview.next_runs_local.join(' · ')}</div>}
          {preview?.error && <div className="text-xs text-rose-300">{preview.error}</div>}
          <Field label={t('prompt')}><textarea className={inputCls} value={form.prompt} onChange={(e) => setForm({ ...form, prompt: e.target.value })} /></Field>
          <Button kind="primary" onClick={create}>{t('create')}</Button>
          {msg && <div className="text-xs text-amber-200 break-all select-text">{msg}</div>}
        </Card>
        {list.length ? list.map((r) => (
          <Card key={r.id} className="mb-2">
            <div className="flex justify-between gap-2 flex-wrap">
              <div><b>{r.name}</b> · {bot(r.bot_id)?.name} · <span className={r.enabled ? 'text-emerald-300' : 'text-zinc-500'}>{r.enabled ? t('enabled') : t('disabled')}</span>
                <div className="text-xs text-zinc-400">{r.kind === 'event' ? 'webhook' : (r.schedule.cron || r.schedule.at)} · {r.timezone}</div>
                {r.preview?.length > 0 && <div className="text-xs text-zinc-500">{t('nextRuns')}: {r.preview.join(' · ')}</div>}
              </div>
              <div className="flex gap-1 flex-wrap">
                <Button small onClick={() => api(`/routines/${r.id}`, { method: 'PATCH', body: { enabled: !r.enabled } }).then(load)}>{r.enabled ? t('pause') : t('resume')}</Button>
                <Button small onClick={() => api(`/routines/${r.id}/simulate`, { method: 'POST' }).then((s) => setMsg(JSON.stringify(s, null, 1)))}>{t('simulate')}</Button>
                <Button small kind="primary" onClick={() => api(`/routines/${r.id}/test-run`, { method: 'POST' }).then(load)}>{t('testRun')}</Button>
                <Button small onClick={() => api(`/routines/${r.id}/history`).then((h) => setHist({ ...hist, [r.id]: h }))}>{t('history')}</Button>
                <Button small kind="danger" onClick={() => confirm('Delete?') && api(`/routines/${r.id}`, { method: 'DELETE' }).then(load)}>{t('delete')}</Button>
              </div>
            </div>
            {hist[r.id] && <div className="mt-2 text-xs space-y-1">{hist[r.id].map((h) => (
              <div key={h.id} className="flex gap-2 flex-wrap"><span>{fmtTime(h.scheduled_for || h.created_at)}</span><span>{h.trigger}</span>
                <TaskBadge status={h.task_status || h.status} /><span className="text-zinc-400 truncate max-w-[50%]">{h.result_text || h.task_error || h.error}</span>
                <span className="text-zinc-500">cost {Number(h.cost || 0).toFixed(4)}</span></div>))}</div>}
          </Card>
        )) : <Empty />}
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
          <div className="text-xs text-zinc-500">{m.scope}{m.bot_id ? ` · ${ws.bots.find((b) => b.id === m.bot_id)?.name || m.bot_id}` : ''} · {m.source} · {fmtTime(m.updated_at)}</div>
          <div className="text-sm whitespace-pre-wrap select-text">{m.content}</div>
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
            <pre className="text-xs text-zinc-400 whitespace-pre-wrap max-h-32 overflow-y-auto">{s.instructions}</pre>
          </Card>
        ))}
      </Section>
      <Section title="Teach a task">
        <Card className="space-y-2">
          <div className="text-xs text-zinc-400">Turns the tool steps of a finished run into an editable DRAFT skill. Sensitive values are redacted and typed text becomes inputs. Test the draft on safe data.</div>
          <input className={inputCls} placeholder="run id (from task details)" value={teach.run_id} onChange={(e) => setTeach({ ...teach, run_id: e.target.value })} />
          <input className={inputCls} placeholder={t('name')} value={teach.name} onChange={(e) => setTeach({ ...teach, name: e.target.value })} />
          <label className="text-sm flex gap-2 items-center"><input type="checkbox" checked={teach.consent} onChange={(e) => setTeach({ ...teach, consent: e.target.checked })} /> I consent to using this recording</label>
          <Button disabled={!teach.consent} onClick={() => api('/skills/teach', { method: 'POST', body: teach }).then(reloadSkills).catch((e) => setMsg(e.message))}>{t('create')}</Button>
        </Card>
        {msg && <div className="text-xs text-rose-300">{msg}</div>}
      </Section>
    </div>
  );
}
