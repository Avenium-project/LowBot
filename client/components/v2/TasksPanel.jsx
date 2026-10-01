'use client';
import { useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { BotBlob, Button, Card, Empty, Section, TaskBadge, botLabel, fmtTime } from './ui';

function StepRow({ s }) {
  const out = s.output || {};
  return (
    <div className="text-[13px] border-l-2 border-white/10 pl-2 py-1">
      <div className="flex gap-2 items-center flex-wrap">
        <span className="text-zinc-500">#{s.seq}</span>
        <span className="font-mono">{s.kind === 'model' ? 'model' : s.tool_name}</span>
        <TaskBadge status={s.status} />
      </div>
      {s.kind === 'model' && out.text && <div className="text-zinc-400 whitespace-pre-wrap line-clamp-3">{out.text}</div>}
      {s.kind === 'model' && out.tool_calls?.length > 0 && <div className="text-zinc-500">→ {out.tool_calls.map((c) => c.name).join(', ')}</div>}
      {s.kind === 'tool' && <details><summary className="cursor-pointer text-zinc-500">input / output</summary>
        <pre className="whitespace-pre-wrap break-all text-[12px] text-zinc-400">{JSON.stringify(s.input?.arguments, null, 1)}</pre>
        <pre className="whitespace-pre-wrap break-all text-[12px] text-zinc-300 max-h-48 overflow-y-auto">{JSON.stringify(out, null, 1)}</pre>
      </details>}
      {s.error && <div className="text-rose-400">{s.error}</div>}
    </div>
  );
}

function Tree({ node, botsById, depth = 0 }) {
  const b = botsById[node.task.bot_id];
  return (
    <div style={{ marginLeft: depth * 12 }} className="text-[13px] py-0.5">
      {depth > 0 && '↳ '}{botLabel(b)}: {node.task.title} <TaskBadge status={node.run?.status || node.task.status} />
      {node.children.map((c) => <Tree key={c.task.id} node={c} botsById={botsById} depth={depth + 1} />)}
    </div>
  );
}

export function TaskDetail({ taskId, ws, onClose }) {
  const { t } = useT();
  const [d, setD] = useState(null);
  const botsById = useMemo(() => Object.fromEntries(ws.bots.map((b) => [b.id, b])), [ws.bots]);
  useEffect(() => { api(`/tasks/${taskId}`).then(setD).catch(() => setD(null)); }, [taskId, ws.tick]);
  if (!d) return <Empty />;
  const run = d.run || {};
  const act = (p, body) => api(`/tasks/${taskId}/${p}`, { method: 'POST', body }).then(() => ws.reload());
  const resolve = (outcome) => api(`/runs/${run.id}/resolve`, { method: 'POST', body: { outcome } }).then(() => ws.reload());
  const active = !['completed', 'failed', 'cancelled'].includes(d.task.status);
  return (
    <div className="space-y-3">
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="font-semibold">{d.task.title}</div>
          <div className="text-[13px] text-zinc-400">{botsById[d.task.bot_id]?.name} · {fmtTime(d.task.created_at)} · <TaskBadge status={run.status || d.task.status} /></div>
        </div>
        {onClose && <Button small kind="ghost" onClick={onClose}>✕</Button>}
      </div>
      {active && <div className="flex gap-2 flex-wrap">
        <Button small kind="danger" onClick={() => act('cancel')}>{t('stop')}</Button>
        {run.status === 'paused' ? <Button small onClick={() => act('resume')}>{t('resume')}</Button>
          : <Button small onClick={() => act('pause')}>{t('pause')}</Button>}
      </div>}
      {run.status === 'unknown_outcome' && (
        <Card className="border-rose-500/40">
          <div className="text-[15px] font-semibold text-rose-200">{t('unknownOutcome')}</div>
          <div className="text-[13px] text-zinc-400 mb-2">{run.waiting?.tool}: {run.waiting?.reason}</div>
          <div className="flex gap-2 flex-wrap">
            <Button small kind="success" onClick={() => resolve('done')}>{t('markDone')}</Button>
            <Button small onClick={() => resolve('not_done')}>{t('markNotDone')}</Button>
            <Button small kind="danger" onClick={() => resolve('abandon')}>{t('abandon')}</Button>
          </div>
        </Card>
      )}
      {d.task.result_text && <Card><div className="text-[13px] text-zinc-400">{t('result')}</div><div className="text-[15px] whitespace-pre-wrap select-text">{d.task.result_text}</div></Card>}
      {d.task.error && <Card className="border-rose-500/40"><div className="text-[13px] text-zinc-400">{t('error')}</div><div className="text-[15px] text-rose-200 whitespace-pre-wrap">{d.task.error}</div></Card>}
      {(d.children.length > 0 || d.task.parent_task_id) && <Section title={t('subtasks')}><Tree node={d} botsById={botsById} /></Section>}
      <Section title={t('steps')}>{(d.steps || []).map((s) => <StepRow key={s.id} s={s} />)}</Section>
    </div>
  );
}

export default function TasksPanel({ ws }) {
  const { lang } = useT();
  const [sel, setSel] = useState(null);
  const botsById = useMemo(() => Object.fromEntries(ws.bots.map((b) => [b.id, b])), [ws.bots]);
  if (sel) return <TaskDetail taskId={sel} ws={ws} onClose={() => setSel(null)} />;
  const active = ws.tasks.filter((x) => !['completed', 'failed', 'cancelled'].includes(x.status));
  const recent = ws.tasks.filter((x) => ['completed', 'failed', 'cancelled'].includes(x.status)).slice(0, 40);
  const row = (x) => (
    <button key={x.id} onClick={() => setSel(x.id)} className="w-full text-left rounded-lg px-2 py-2 hover:bg-white/5 flex items-center gap-2 min-h-[44px]">
      <BotBlob bot={botsById[x.bot_id]} size={30} />
      <span className="flex-1 min-w-0">
        <span className="block text-[15px] truncate">{x.title}</span>
        <span className="block text-[12px] text-zinc-500">{botsById[x.bot_id]?.name} · {x.requester_type}{x.parent_task_id ? ' · ↳' : ''} · {fmtTime(x.created_at)}</span>
      </span>
      <TaskBadge status={x.run_status || x.status} />
    </button>
  );
  return (
    <div>
      <Section title={`${'Active'} (${active.length})`}>{active.length ? active.map(row) : <Empty />}</Section>
      <Section title={'Recent'}>{recent.length ? recent.map(row) : <Empty />}</Section>
    </div>
  );
}
