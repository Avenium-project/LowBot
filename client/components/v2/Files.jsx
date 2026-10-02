'use client';
// Files: a viewer for any file (image, text/Markdown, or "open in another app") and a simple
// explorer over the files bots sent and the shared workspace folder.
import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { FiChevronRight, FiDownload, FiExternalLink, FiFile, FiFileText, FiFolder, FiImage, FiSearch, FiShare2, FiX } from 'react-icons/fi';
import { api, fetchBlob, openBlob, saveBlob } from '../../lib/v2/api';
import { BotBlob, cls, shortTime } from './ui';

const TEXT_EXT = ['md', 'markdown', 'txt', 'log', 'csv', 'tsv', 'json', 'yaml', 'yml', 'toml', 'xml', 'html', 'css', 'js', 'jsx', 'ts', 'tsx',
  'py', 'sh', 'java', 'kt', 'c', 'h', 'cpp', 'go', 'rs', 'rb', 'php', 'sql', 'ini', 'cfg', 'env'];

const MD = {
  a: ({ href, children }) => <a href={href} target="_blank" rel="noopener noreferrer" className="text-sky-400 underline underline-offset-2">{children}</a>,
  table: ({ children }) => <div className="lb-table"><table>{children}</table></div>,
};

export function extOf(name = '') { const i = name.lastIndexOf('.'); return i > 0 ? name.slice(i + 1).toLowerCase() : ''; }
export function kindOf(name, mime = '') {
  const ext = extOf(name);
  if (/^image\//.test(mime) || ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(ext)) return 'image';
  if (/^text\//.test(mime) || /json|xml|yaml|javascript/.test(mime) || TEXT_EXT.includes(ext)) return 'text';
  if (ext === 'pdf' || mime === 'application/pdf') return 'pdf';
  return 'other';
}
export function fmtSize(n) { return n == null ? '' : n > 1e6 ? `${(n / 1e6).toFixed(1)} MB` : n > 1e3 ? `${Math.round(n / 1e3)} KB` : `${n} B`; }

export function FileIcon({ name, mime, size = 40 }) {
  const k = kindOf(name, mime);
  const ext = extOf(name).slice(0, 4).toUpperCase();
  const tone = k === 'image' ? 'text-emerald-300' : k === 'pdf' ? 'text-rose-300' : k === 'text' ? 'text-sky-300' : 'text-zinc-300';
  return (
    <span className="shrink-0 rounded-xl bg-white/10 flex flex-col items-center justify-center" style={{ width: size, height: size }}>
      {k === 'image' ? <FiImage className={tone} /> : k === 'text' ? <FiFileText className={tone} /> : <FiFile className={tone} />}
      {ext && size >= 40 && <span className={cls('text-[9px] font-semibold leading-none mt-0.5', tone)}>{ext}</span>}
    </span>
  );
}

/** Full-screen viewer. file: { path (API download path), name, mime, size } */
export function FileViewer({ file, onClose }) {
  const [blob, setBlob] = useState(null);
  const [url, setUrl] = useState(null);
  const [text, setText] = useState(null);
  const [err, setErr] = useState('');
  const [note, setNote] = useState('');
  const kind = kindOf(file.name, file.mime);
  useEffect(() => {
    let u;
    let alive = true;
    fetchBlob(file.path).then(async (b) => {
      if (!alive) return;
      setBlob(b);
      if (kind === 'image') { u = URL.createObjectURL(b); setUrl(u); }
      else if (kind === 'text' && b.size < 2e6) setText(await b.text());
    }).catch((e) => setErr(e.message));
    return () => { alive = false; if (u) URL.revokeObjectURL(u); };
  }, [file.path, kind]);
  const act = async (fn, done) => {
    setErr(''); setNote('');
    try { await fn(); if (done) setNote(done); } catch (e) { setErr(e.message); }
  };
  const ext = extOf(file.name);
  // Portal: a fixed overlay inside an animated (transformed) chat bubble would be clipped to it.
  return createPortal(
    <div className="fixed inset-0 z-[55] bg-[#0d0d0d] flex flex-col lb-backdrop-in">
      <header className="flex items-center gap-3 px-4 pb-3 border-b border-white/10" style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}>
        <button type="button" aria-label="Close" onClick={onClose} className="lb-press h-11 w-11 shrink-0 rounded-full bg-[#2a2a2a] flex items-center justify-center text-xl"><FiX /></button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[16px] font-medium">{file.name}</div>
          <div className="text-[12px] text-zinc-500">{fmtSize(blob?.size ?? file.size)}</div>
        </div>
      </header>
      <div className="flex-1 min-h-0 overflow-auto">
        {!blob && !err && <div className="lb-skeleton m-4 h-[50vh] rounded-[18px]" />}
        {kind === 'image' && url && <div className="min-h-full flex items-center justify-center p-2"><img src={url} alt={file.name} className="max-w-full max-h-[80vh] object-contain" /></div>}
        {kind === 'text' && text != null && (['md', 'markdown'].includes(ext)
          ? <div className="lb-md lb-selectable prose prose-invert max-w-none p-4 text-[16px]"><ReactMarkdown remarkPlugins={[remarkGfm]} components={MD}>{text}</ReactMarkdown></div>
          : <pre className="lb-selectable p-4 text-[13px] leading-relaxed font-mono text-zinc-200 whitespace-pre-wrap break-words">{text}</pre>)}
        {blob && (kind === 'pdf' || kind === 'other' || (kind === 'text' && text == null)) && (
          <div className="h-full flex flex-col items-center justify-center gap-4 p-8 text-center">
            <FileIcon name={file.name} mime={file.mime} size={88} />
            <div className="text-[15px] text-zinc-400">Open it in another app on your phone.</div>
            <button type="button" onClick={() => act(() => openBlob(blob, file.name, 'view'))} className="lb-press rounded-full bg-white text-black px-6 py-3 font-semibold">Open</button>
          </div>)}
        {err && <div className="m-4 rounded-2xl bg-rose-950/40 px-4 py-3 text-[14px] text-rose-300">{err}</div>}
      </div>
      <div className="grid grid-cols-3 gap-2 px-4 pt-3 border-t border-white/10" style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}>
        {[[<FiExternalLink key="o" />, 'Open in…', () => openBlob(blob, file.name, 'view'), ''],
          [<FiDownload key="d" />, 'Save', () => saveBlob(blob, file.name), 'Saved to Downloads/LowBot'],
          [<FiShare2 key="s" />, 'Share', () => openBlob(blob, file.name, 'share'), '']].map(([icon, label, fn, done]) => (
          <button key={label} type="button" disabled={!blob} onClick={() => act(fn, done)}
            className="lb-press flex flex-col items-center gap-1 rounded-2xl bg-[#1f1f1f] py-2.5 text-[13px] disabled:opacity-40">
            <span className="text-[18px]">{icon}</span>{label}</button>))}
        {note && <div className="col-span-3 text-center text-[12px] text-emerald-400">{note}</div>}
      </div>
    </div>,
    document.body,
  );
}

function FromBots({ ws, onOpen, q }) {
  const [rows, setRows] = useState(null);
  useEffect(() => { api('/artifacts').then(setRows).catch(() => setRows([])); }, [ws.tick]);
  const list = useMemo(() => (rows || []).filter((a) => !q || a.name.toLowerCase().includes(q.toLowerCase())), [rows, q]);
  if (!rows) return <div className="lb-skeleton h-40 rounded-[22px]" />;
  if (!list.length) return <div className="text-center text-zinc-500 text-[15px] mt-12">{q ? 'No matching files' : 'No files from bots yet'}</div>;
  return (
    <div className="rounded-[22px] bg-[#1f1f1f] overflow-hidden lb-stagger">
      {list.map((a) => {
        const bot = ws.bots.find((b) => b.id === a.bot_id);
        return (
          <button key={a.id} type="button" onClick={() => onOpen({ path: `/artifacts/${a.id}/download`, name: a.name, mime: a.mime, size: a.size })}
            className="lb-press w-full flex items-center gap-3 px-4 py-3 text-left border-b border-white/5 last:border-b-0">
            <FileIcon name={a.name} mime={a.mime} />
            <span className="flex-1 min-w-0">
              <span className="block truncate text-[16px]">{a.name}</span>
              <span className="flex items-center gap-1.5 text-[12px] text-zinc-500">{bot && <BotBlob bot={bot} size={14} still />}{bot?.name || 'You'} · {fmtSize(a.size)} · {shortTime(a.created_at)}</span>
            </span>
          </button>);
      })}
    </div>
  );
}

function WorkspaceBrowser({ ws, onOpen, q }) {
  const [path, setPath] = useState('');
  const [data, setData] = useState(null);
  const [err, setErr] = useState('');
  useEffect(() => { setData(null); api(`/workspace/files?path=${encodeURIComponent(path)}`).then((d) => { setData(d); setErr(''); }).catch((e) => setErr(e.message)); }, [path, ws.tick]);
  const crumbs = path ? path.split('/') : [];
  const items = (data?.items || []).filter((f) => !q || f.name.toLowerCase().includes(q.toLowerCase()));
  return (
    <>
      <div className="flex items-center gap-1 flex-wrap px-1 pb-3 text-[15px]">
        <button type="button" onClick={() => setPath('')} className={cls('lb-press', path ? 'text-sky-400' : 'text-zinc-200')}>workspace</button>
        {crumbs.map((c, i) => (
          <span key={i} className="flex items-center gap-1"><FiChevronRight className="text-zinc-600" />
            <button type="button" onClick={() => setPath(crumbs.slice(0, i + 1).join('/'))} className={cls('lb-press', i < crumbs.length - 1 ? 'text-sky-400' : 'text-zinc-200')}>{c}</button></span>))}
      </div>
      {err ? <div className="text-[14px] text-rose-400 px-2">{err}</div>
        : !data ? <div className="lb-skeleton h-40 rounded-[22px]" />
          : !items.length ? <div className="text-center text-zinc-500 text-[15px] mt-12">{q ? 'No matching files' : 'This folder is empty'}</div>
            : (
              <div className="rounded-[22px] bg-[#1f1f1f] overflow-hidden lb-stagger">
                {items.map((f) => (
                  <button key={f.path} type="button"
                    onClick={() => (f.dir ? setPath(f.path) : onOpen({ path: `/workspace/file?path=${encodeURIComponent(f.path)}`, name: f.name, size: f.size }))}
                    className="lb-press w-full flex items-center gap-3 px-4 py-3 text-left border-b border-white/5 last:border-b-0">
                    {f.dir ? <span className="h-10 w-10 shrink-0 rounded-xl bg-sky-500/15 flex items-center justify-center text-sky-300 text-lg"><FiFolder /></span> : <FileIcon name={f.name} />}
                    <span className="flex-1 min-w-0">
                      <span className="block truncate text-[16px]">{f.name}</span>
                      <span className="block text-[12px] text-zinc-500">{f.dir ? `${f.size} item${f.size === 1 ? '' : 's'}` : fmtSize(f.size)} · {shortTime(f.updated_at)}</span>
                    </span>
                    {f.dir && <FiChevronRight className="text-zinc-500" />}
                  </button>))}
              </div>)}
    </>
  );
}

export function FilesExplorer({ ws, local }) {
  const [tab, setTab] = useState('bots');
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(null);
  const tabs = local ? [['bots', 'From bots'], ['workspace', 'Workspace']] : [['bots', 'From bots']];
  return (
    <div>
      <div className="flex items-center rounded-full bg-[#2a2a2a] px-4 h-11 mb-3">
        <FiSearch className="text-zinc-400 mr-2" />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search files" className="flex-1 min-w-0 bg-transparent outline-none text-[15px]" />
      </div>
      {tabs.length > 1 && (
        <div className="flex rounded-full bg-[#1f1f1f] p-1 mb-4">
          {tabs.map(([k, l]) => <button key={k} type="button" onClick={() => setTab(k)}
            className={cls('flex-1 rounded-full py-2 text-[14px] transition-colors', tab === k ? 'bg-white text-black font-medium' : 'text-zinc-300')}>{l}</button>)}
        </div>)}
      {tab === 'bots' ? <FromBots ws={ws} onOpen={setOpen} q={q} /> : <WorkspaceBrowser ws={ws} onOpen={setOpen} q={q} />}
      {open && <FileViewer file={open} onClose={() => setOpen(null)} />}
    </div>
  );
}
