'use client';
// Shared workspaces: a folder of files + AGENTS.md rules that every member bot follows.
// Bots can create them too (project.create) and add each other (project.add_member).
import { useEffect, useState } from 'react';
import { FiChevronRight, FiFileText, FiFolder, FiMessageCircle, FiTrash2 } from 'react-icons/fi';
import { api } from '../../lib/v2/api';
import { BotBlob, Button, Card, Empty, Field, Section, Toggle, botLabel, cls, fmtTime, inputCls } from './ui';

function Members({ ws, ids, size = 28 }) {
  const bots = ids.map((id) => ws.bots.find((b) => b.id === id)).filter(Boolean);
  if (!bots.length) return <span className="text-[13px] text-zinc-500">No bots yet</span>;
  return (
    <span className="flex -space-x-2">
      {bots.slice(0, 6).map((b) => <span key={b.id} className="rounded-full ring-2 ring-[#1f1f1f]"><BotBlob bot={b} size={size} still /></span>)}
      {bots.length > 6 && <span className="ml-3 text-[13px] text-zinc-400 self-center">+{bots.length - 6}</span>}
    </span>
  );
}

function MemberPicker({ ws, value, onChange }) {
  const toggle = (id) => onChange(value.includes(id) ? value.filter((x) => x !== id) : [...value, id]);
  return (
    <div className="rounded-[22px] bg-[#1f1f1f] overflow-hidden">
      {ws.bots.map((b) => (
        <div key={b.id} className="flex items-center gap-3 px-4 py-2.5 border-b border-white/5 last:border-b-0">
          <BotBlob bot={b} size={34} still />
          <span className="flex-1 truncate text-[16px]">{botLabel(b)}</span>
          <Toggle on={value.includes(b.id)} onChange={() => toggle(b.id)} label={`Member: ${b.name}`} />
        </div>))}
    </div>
  );
}

function Detail({ ws, name, onBack, onOpenConversation }) {
  const [w, setW] = useState(null);
  const [rules, setRules] = useState('');
  const [files, setFiles] = useState([]);
  const [err, setErr] = useState('');
  const [saved, setSaved] = useState('');
  const [confirm, setConfirm] = useState(false);
  const path = `/projects/${encodeURIComponent(name)}`;
  const load = () => Promise.all([api(path), api(`${path}/files`)])
    .then(([x, f]) => { setW(x); setRules(x.agents_md); setFiles(f); setErr(''); }).catch((e) => setErr(e.message));
  useEffect(() => { load(); }, [name, ws.tick]); // eslint-disable-line react-hooks/exhaustive-deps

  const patch = (body, note) => api(path, { method: 'PATCH', body }).then((x) => { setW(x); setSaved(note); setTimeout(() => setSaved(''), 1800); }).catch((e) => setErr(e.message));
  const chat = async () => {
    try {
      const c = await api(`${path}/chat`, { method: 'POST' });
      await ws.reload();
      onOpenConversation(c.id);
    } catch (e) { setErr(e.message); }
  };
  const remove = () => api(path, { method: 'DELETE' }).then(onBack).catch((e) => setErr(e.message));

  if (!w) return err ? <Empty>{err}</Empty> : <div className="lb-skeleton h-[40vh] rounded-[22px]" />;
  return (
    <div className="lb-rise space-y-6">
      <button type="button" onClick={onBack} className="lb-press text-[15px] text-sky-400 px-1">‹ All workspaces</button>
      <div className="flex items-center gap-4 px-1">
        <span className="h-14 w-14 rounded-2xl bg-[#2a2a2a] flex items-center justify-center text-2xl text-sky-400"><FiFolder /></span>
        <div className="flex-1 min-w-0">
          <div className="text-[22px] font-semibold truncate">{w.name}</div>
          <div className="text-[13px] text-zinc-500">workspace/{w.folder} · {w.files_count} files</div>
        </div>
      </div>
      <Button kind="primary" className="w-full" onClick={chat}><FiMessageCircle /> Open team chat</Button>

      <Section title="Members">
        <MemberPicker ws={ws} value={w.members} onChange={(members) => patch({ members }, 'Members saved')} />
      </Section>

      <Section title="Shared rules · AGENTS.md">
        <textarea className={cls(inputCls, 'h-44 font-mono text-[14px] lb-selectable')} value={rules} onChange={(e) => setRules(e.target.value)}
          placeholder="How every bot works here: conventions, where files go, what to check before saying done." />
        <div className="flex items-center gap-3 mt-2">
          <Button disabled={rules === w.agents_md} onClick={() => patch({ agents_md: rules }, 'Rules saved')}>Save rules</Button>
          {saved && <span className="lb-rise text-[13px] text-emerald-400">{saved}</span>}
        </div>
      </Section>

      <Section title="Shared files">
        <div className="rounded-[22px] bg-[#1f1f1f] overflow-hidden">
          {files.length ? files.slice(0, 200).map((f) => (
            <div key={f.path} className="flex items-center gap-3 px-4 py-3 border-b border-white/5 last:border-b-0">
              <FiFileText className="text-zinc-500 shrink-0" />
              <span className="flex-1 min-w-0 truncate text-[15px]">{f.path}</span>
              <span className="text-[12px] text-zinc-500 shrink-0">{(f.size / 1024).toFixed(1)} KB · {fmtTime(f.updated_at)}</span>
            </div>))
            : <div className="px-5 py-4 text-zinc-500 text-[15px]">No files yet. Bots write them with workspace.write into {w.folder}</div>}
        </div>
      </Section>

      {err && <div className="text-[13px] text-rose-400 px-2">{err}</div>}
      {confirm
        ? <Card className="lb-rise space-y-3"><div className="text-[15px]">Delete {w.name}, its {w.files_count} files and rules? Chats stay, but are unbound.</div>
            <div className="flex gap-2"><Button kind="danger" onClick={remove}>Delete workspace</Button><Button onClick={() => setConfirm(false)}>Cancel</Button></div></Card>
        : <button type="button" onClick={() => setConfirm(true)} className="lb-press flex items-center gap-2 px-2 text-rose-400 text-[15px]"><FiTrash2 /> Delete workspace</button>}
    </div>
  );
}

export default function WorkspacesPanel({ ws, onOpenConversation }) {
  const [list, setList] = useState(null);
  const [open, setOpen] = useState(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: '', agents_md: '', members: [] });
  const [err, setErr] = useState('');
  const load = () => api('/projects').then(setList).catch((e) => setErr(e.message));
  useEffect(() => { load(); }, [ws.tick]);

  const create = async () => {
    setErr('');
    try {
      const w = await api('/projects', { method: 'POST', body: form });
      setCreating(false); setForm({ name: '', agents_md: '', members: [] });
      await load(); setOpen(w.name);
    } catch (e) { setErr(e.message); }
  };

  if (open) return <Detail ws={ws} name={open} onBack={() => { setOpen(null); load(); }} onOpenConversation={onOpenConversation} />;
  if (!list) return err ? <Empty>{err}</Empty> : <div className="lb-skeleton h-[40vh] rounded-[22px]" />;
  return (
    <div>
      <Section title="Workspaces" actions={!creating && <Button small kind="primary" onClick={() => setCreating(true)}>+ New workspace</Button>}>
        {creating && (
          <Card className="lb-rise space-y-3 mb-4">
            <Field label="Name"><input className={inputCls} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="e.g. Launch plan" /></Field>
            <Field label="Shared rules (AGENTS.md)" hint="Optional — bots can write them later.">
              <textarea className={cls(inputCls, 'h-28 lb-selectable')} value={form.agents_md} onChange={(e) => setForm({ ...form, agents_md: e.target.value })} /></Field>
            <Field label="Members"><MemberPicker ws={ws} value={form.members} onChange={(members) => setForm({ ...form, members })} /></Field>
            <div className="flex gap-2"><Button kind="primary" disabled={!form.name.trim()} onClick={create}>Create</Button><Button onClick={() => setCreating(false)}>Cancel</Button></div>
          </Card>)}
        {err && <div className="text-[13px] text-rose-400 px-2 mb-3">{err}</div>}
        {list.length ? (
          <div className="rounded-[22px] bg-[#1f1f1f] overflow-hidden lb-stagger">
            {list.map((w) => (
              <button key={w.name} type="button" onClick={() => setOpen(w.name)} className="lb-press w-full flex items-center gap-4 px-4 py-3.5 text-left border-b border-white/5 last:border-b-0">
                <span className="h-11 w-11 shrink-0 rounded-2xl bg-[#2a2a2a] flex items-center justify-center text-xl text-sky-400"><FiFolder /></span>
                <span className="flex-1 min-w-0">
                  <span className="block text-[17px] truncate">{w.name}</span>
                  <span className="block text-[13px] text-zinc-500 truncate">{w.files_count} files · {(w.agents_md || '').split('\n').find((l) => l.trim() && !l.startsWith('#')) || 'no rules yet'}</span>
                </span>
                <Members ws={ws} ids={w.members} size={26} />
                <FiChevronRight className="text-zinc-500 shrink-0" />
              </button>))}
          </div>)
          : !creating && <Empty>No workspaces yet. Create one, or ask a bot to set one up for a project.</Empty>}
      </Section>
    </div>
  );
}
