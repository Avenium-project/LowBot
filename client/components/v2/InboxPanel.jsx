'use client';
import { useState } from 'react';
import { api } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Empty, Section, botLabel, fmtTime, inputCls } from './ui';

export function ApprovalCard({ a, ws }) {
  const { t } = useT();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(JSON.stringify(a.display, null, 2));
  const [err, setErr] = useState('');
  const bot = ws.bots.find((b) => b.id === a.bot_id);
  const decide = async (decision) => {
    setErr('');
    try { await api(`/approvals/${a.id}/decide`, { method: 'POST', body: { decision, args_hash: a.args_hash } }); ws.reload(); }
    catch (e) { setErr(e.message); ws.reload(); }
  };
  const saveEdit = async () => {
    setErr('');
    try {
      await api(`/approvals/${a.id}/edit`, { method: 'POST', body: { arguments: JSON.parse(draft) } });
      setEditing(false); ws.reload();
    } catch (e) { setErr(e.message); }
  };
  return (
    <Card className="border-amber-500/30 mb-2">
      <div className="text-[13px] text-zinc-400">{botLabel(bot)} · {fmtTime(a.created_at)}</div>
      <div className="font-semibold text-[15px] my-1">{a.summary}</div>
      <dl className="text-[13px] grid grid-cols-[auto,1fr] gap-x-2 gap-y-0.5 mb-2">
        <dt className="text-zinc-500">{t('effect')}</dt><dd>{a.effect || a.tool}</dd>
        {a.target && <><dt className="text-zinc-500">{t('target')}</dt><dd className="break-all">{a.target}</dd></>}
        <dt className="text-zinc-500">{t('expires')}</dt><dd>{fmtTime(a.expires_at)}</dd>
      </dl>
      {editing
        ? <textarea className={`${inputCls} font-mono text-[13px] h-32`} value={draft} onChange={(e) => setDraft(e.target.value)} />
        : <pre className="text-[13px] bg-black/30 rounded p-2 whitespace-pre-wrap break-all max-h-40 overflow-y-auto">{JSON.stringify(a.display, null, 2)}</pre>}
      {err && <div className="text-[13px] text-rose-400 mt-1">{err}</div>}
      <div className="flex gap-2 mt-2 flex-wrap">
        {editing ? <>
          <Button small kind="primary" onClick={saveEdit}>{t('save')}</Button>
          <Button small onClick={() => setEditing(false)}>{t('cancel')}</Button>
        </> : <>
          <Button small kind="success" onClick={() => decide('approve')}>{t('approve')}</Button>
          <Button small kind="danger" onClick={() => decide('deny')}>{t('deny')}</Button>
          <Button small onClick={() => setEditing(true)}>{t('edit')}</Button>
        </>}
      </div>
    </Card>
  );
}

export function ElicitationCard({ e, ws }) {
  const { t } = useT();
  const props = e.schema?.properties || {};
  const [vals, setVals] = useState({});
  const [consent, setConsent] = useState(false);
  const respond = (action) => api(`/mcp/elicitations/${e.id}`, {
    method: 'POST', body: { action, content: action === 'accept' && e.mode === 'form' ? vals : undefined },
  }).then(() => ws.reload());
  return (
    <Card className="border-sky-500/30 mb-2">
      <div className="text-[13px] text-zinc-400">{t('elicitation')} · {fmtTime(e.created_at)}</div>
      <div className="text-[15px] my-1">{e.message}</div>
      {e.mode === 'form' && Object.entries(props).map(([k, spec]) => (
        <input key={k} className={`${inputCls} mb-1`} placeholder={spec.title || spec.description || k}
          type={spec.type === 'number' || spec.type === 'integer' ? 'number' : 'text'}
          onChange={(ev) => setVals({ ...vals, [k]: spec.type === 'number' || spec.type === 'integer' ? Number(ev.target.value) : ev.target.value })} />
      ))}
      {e.mode === 'url' && (
        <div className="text-[13px] mb-2">
          <div>{t('domain')}: <b className="text-amber-400">{e.url_domain}</b></div>
          <label className="flex items-center gap-2 mt-1"><input type="checkbox" checked={consent} onChange={(ev) => setConsent(ev.target.checked)} />
            {t('openLink')}?</label>
          {consent && <a href={e.url} target="_blank" rel="noopener noreferrer" className="underline text-sky-400 break-all">{e.url}</a>}
        </div>
      )}
      <div className="flex gap-2 flex-wrap">
        <Button small kind="success" disabled={e.mode === 'url' && !consent} onClick={() => respond('accept')}>{t('accept')}</Button>
        <Button small kind="danger" onClick={() => respond('decline')}>{t('decline')}</Button>
        <Button small onClick={() => respond('cancel')}>{t('cancel')}</Button>
      </div>
    </Card>
  );
}

export default function InboxPanel({ ws, onOpenTask }) {
  const { t } = useT();
  const items = ws.notifications.items || [];
  return (
    <div>
      <Section title={`${t('approvals')} (${ws.approvals.length})`}>
        {ws.approvals.length ? ws.approvals.map((a) => <ApprovalCard key={a.id} a={a} ws={ws} />) : <Empty />}
      </Section>
      {ws.elicitations.length > 0 && <Section title={t('elicitation')}>{ws.elicitations.map((e) => <ElicitationCard key={e.id} e={e} ws={ws} />)}</Section>}
      <Section title={`${t('notifications')} (${ws.notifications.unread})`}
        actions={<Button small onClick={() => api('/notifications/read-all', { method: 'POST' }).then(() => ws.reload())}>{t('markAllRead')}</Button>}>
        {items.length ? <div className="lb-stagger rounded-[22px] bg-[#1f1f1f] overflow-hidden">{items.map((n) => (
          <button key={n.id} onClick={() => { api(`/notifications/${n.id}/read`, { method: 'POST' }).then(() => ws.reload()); if (n.task_id) onOpenTask?.(n.task_id); }}
            className={`lb-press w-full flex items-start gap-3 text-left px-5 py-3.5 border-b border-white/5 last:border-b-0 hover:bg-white/[0.03] ${n.read_at ? 'opacity-60' : ''}`}>
            <span className={`mt-2 h-2 w-2 shrink-0 rounded-full ${n.read_at ? 'bg-transparent' : n.kind === 'approval' || n.kind.startsWith('needs') ? 'bg-amber-400' : 'bg-blue-500'}`} />
            <span className="flex-1 min-w-0">
              <span className="block text-[16px] truncate">{n.title}</span>
              {n.body && <span className="block text-[14px] text-zinc-400 line-clamp-2">{n.body}</span>}
              <span className="block text-[12px] text-zinc-500 mt-0.5">{n.kind.replace(/_/g, ' ')} · {fmtTime(n.created_at)}</span>
            </span>
          </button>
        ))}</div> : <Empty />}
      </Section>
    </div>
  );
}
