'use client';
import { useEffect, useState } from 'react';
import { api, saveBlob } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Field, Section, inputCls } from './ui';

const EMPTY = { name: '', avatar: '🤖', role_description: '', instructions: '', provider_profile_id: '', model: '',
  tools: 'workspace.*, web.fetch, memory.*, user.ask, task.*, bot.message, artifact.share, browser.*',
  org_role: '', reports_to: '', computer_mode: 'shared', can_create_bots: false, team_memory_access: false, budget: '' };

export function BotEditor({ ws, bot, onDone }) {
  const { t } = useT();
  const [f, setF] = useState(EMPTY);
  const [providers, setProviders] = useState([]);
  const [err, setErr] = useState('');
  useEffect(() => { api('/providers').then((d) => setProviders(d.profiles)); }, []);
  useEffect(() => {
    if (bot) setF({ ...EMPTY, ...bot, tools: bot.tools.join(', '), provider_profile_id: bot.provider_profile_id || '',
      model: bot.model || '', org_role: bot.org_role || '', reports_to: bot.reports_to || '', budget: bot.budget?.max_cost_per_day ?? '' });
  }, [bot]);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });
  const save = async () => {
    setErr('');
    const body = {
      name: f.name, avatar: f.avatar, role_description: f.role_description, instructions: f.instructions,
      provider_profile_id: f.provider_profile_id || null, model: f.model || null,
      tools: f.tools.split(',').map((x) => x.trim()).filter(Boolean), org_role: f.org_role || null,
      reports_to: f.reports_to || null, computer_mode: f.computer_mode, can_create_bots: f.can_create_bots,
      team_memory_access: f.team_memory_access, budget: f.budget === '' ? {} : { max_cost_per_day: Number(f.budget) },
    };
    try {
      if (bot) await api(`/bots/${bot.id}`, { method: 'PATCH', body });
      else await api('/bots', { method: 'POST', body });
      ws.reload(); onDone?.();
    } catch (e) { setErr(e.message); }
  };
  const action = async (path, method = 'POST') => { await api(`/bots/${bot.id}${path}`, { method }); ws.reload(); onDone?.(); };
  return (
    <Section title={bot ? `${t('edit')}: ${bot.name}` : t('newBot')}>
      <div className="space-y-2">
        <div className="flex gap-2"><div className="w-20"><Field label="Avatar"><input className={inputCls} value={f.avatar} onChange={set('avatar')} /></Field></div>
          <div className="flex-1"><Field label={t('name')}><input className={inputCls} value={f.name} onChange={set('name')} /></Field></div></div>
        <Field label={t('role')}><input className={inputCls} value={f.role_description} onChange={set('role_description')} /></Field>
        <Field label={t('instructions')}><textarea className={`${inputCls} h-24`} value={f.instructions} onChange={set('instructions')} /></Field>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Field label={t('provider')}><select className={inputCls} value={f.provider_profile_id} onChange={set('provider_profile_id')}>
            <option value="">(default)</option>{providers.map((p) => <option key={p.id} value={p.id}>{p.name}{p.is_mock ? ' [mock]' : ''}</option>)}</select></Field>
          <Field label={t('model')}><input className={inputCls} value={f.model} onChange={set('model')} placeholder="(provider default)" /></Field>
          <Field label={t('orgRole')}><select className={inputCls} value={f.org_role} onChange={set('org_role')}>
            <option value="">—</option>{['ceo', 'head', 'manager', 'worker'].map((r) => <option key={r} value={r}>{r}</option>)}</select></Field>
          <Field label={t('reportsTo')}><select className={inputCls} value={f.reports_to} onChange={set('reports_to')}>
            <option value="">—</option>{ws.bots.filter((b) => b.id !== bot?.id).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></Field>
          <Field label={t('computerMode')} hint="shared: common logins (not a security boundary) · isolated: own profile & files"><select className={inputCls} value={f.computer_mode} onChange={set('computer_mode')}>
            <option value="shared">{t('shared')}</option><option value="isolated">{t('isolated')}</option></select></Field>
          <Field label={t('budget')} hint="Only enforced for models with prices entered in Settings."><input className={inputCls} type="number" step="0.01" value={f.budget} onChange={set('budget')} /></Field>
        </div>
        <Field label={t('tools')}><input className={inputCls} value={f.tools} onChange={set('tools')} /></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.can_create_bots} onChange={set('can_create_bots')} /> can create bots (never with more tools than itself)</label>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.team_memory_access} onChange={set('team_memory_access')} /> team knowledge access</label>
        {err && <div className="text-xs text-rose-300">{err}</div>}
        <div className="flex gap-2 flex-wrap">
          <Button kind="primary" onClick={save}>{bot ? t('save') : t('create')}</Button>
          {onDone && <Button onClick={onDone}>{t('cancel')}</Button>}
        </div>
        {bot && <div className="flex gap-2 flex-wrap pt-3 border-t border-white/10">
          <Button small onClick={() => action(bot.paused ? '/resume' : '/pause')}>{bot.paused ? t('resume_bot') : t('pause_bot')}</Button>
          <Button small onClick={() => api(`/bots/${bot.id}`, { method: 'PATCH', body: { hidden: !bot.hidden } }).then(() => { ws.reload(); onDone?.(); })}>{bot.hidden ? t('unhide') : t('hide')}</Button>
          <Button small onClick={() => api(`/bots/${bot.id}`, { method: 'PATCH', body: { pinned: !bot.pinned } }).then(() => { ws.reload(); onDone?.(); })}>{bot.pinned ? t('unpin') : t('pin')}</Button>
          <Button small onClick={() => action('/duplicate')}>{t('duplicate')}</Button>
          <Button small onClick={() => api(`/bots/${bot.id}/export`).then((x) => saveBlob(new Blob([JSON.stringify(x, null, 2)], { type: 'application/json' }), `${bot.handle}.bot.json`))}>{t('export')}</Button>
          <Button small kind="danger" onClick={() => confirm(`${t('delete')} ${bot.name}?`) && action('', 'DELETE')}>{t('delete')}</Button>
        </div>}
      </div>
    </Section>
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
          onChange={(e) => setSel(e.target.checked ? [...sel, b.id] : sel.filter((x) => x !== b.id))} />{b.avatar} {b.name} <span className="text-xs text-zinc-500">@{b.handle}</span></label>))}</div>
      {err && <div className="text-xs text-rose-300">{err}</div>}
      <div className="flex gap-2 mt-2"><Button kind="primary" disabled={!sel.length} onClick={create}>{t('create')}</Button>{onDone && <Button onClick={() => onDone()}>{t('cancel')}</Button>}</div>
    </Section>
  );
}
