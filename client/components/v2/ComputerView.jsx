'use client';
import { useEffect, useRef, useState } from 'react';
import { api, fetchBlobUrl } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Empty, Section, inputCls } from './ui';

function LiveSurface({ s, ws }) {
  const { t } = useT();
  const [img, setImg] = useState(null);
  const [err, setErr] = useState('');
  const [text, setText] = useState('');
  const [url, setUrl] = useState('');
  const imgRef = useRef(null);
  const human = s.controller.startsWith('human:');
  const bot = ws.bots.find((b) => b.id === s.bot_id);

  useEffect(() => {
    let alive = true;
    let prev;
    const loop = async () => {
      while (alive) {
        if (document.visibilityState === 'visible' && s.live) {
          try {
            const u = await fetchBlobUrl(`/computers/surfaces/${s.id}/screenshot`);
            if (!alive) { URL.revokeObjectURL(u); return; }
            setImg(u); setErr('');
            if (prev) URL.revokeObjectURL(prev);
            prev = u;
          } catch (e) { setErr(e.message); }
        }
        await new Promise((r) => setTimeout(r, human ? 700 : 1500));
      }
    };
    loop();
    return () => { alive = false; if (prev) URL.revokeObjectURL(prev); };
  }, [s.id, s.live, human]);

  const input = (body) => api(`/computers/surfaces/${s.id}/input`, { method: 'POST', body }).catch((e) => setErr(e.message));
  const click = (e) => {
    if (!human || !imgRef.current) return;
    const r = imgRef.current.getBoundingClientRect();
    const x = ((e.clientX - r.left) / r.width) * imgRef.current.naturalWidth;
    const y = ((e.clientY - r.top) / r.height) * imgRef.current.naturalHeight;
    input({ type: 'click', x, y });
  };
  return (
    <Card className="mb-3">
      <div className="flex items-center justify-between gap-2 mb-2 flex-wrap">
        <div className="text-sm">
          <b>{bot?.avatar} {bot?.name}</b> · {s.mode} · <span className={s.live ? 'text-emerald-300' : 'text-zinc-500'}>{s.live ? '● live' : '○ closed'}</span>
          <div className="text-xs text-zinc-400 break-all">{s.url}</div>
          <div className="text-xs">{t('controlledBy')}: <b className={human ? 'text-amber-300' : 'text-sky-300'}>{human ? 'you' : bot?.name}</b>{s.busy ? ' · acting…' : ''}</div>
        </div>
        {human
          ? <Button small kind="primary" onClick={() => api(`/computers/surfaces/${s.id}/resume`, { method: 'POST' }).then(() => ws.reload())}>{t('giveBack')}</Button>
          : <Button small kind="danger" onClick={() => api(`/computers/surfaces/${s.id}/takeover`, { method: 'POST' }).then(() => ws.reload())}>{t('takeOver')}</Button>}
      </div>
      {img ? <img ref={imgRef} src={img} alt={t('liveView')} onClick={click}
        className={`w-full rounded border border-white/10 ${human ? 'cursor-crosshair' : ''}`} /> : <Empty>{s.live ? '…' : t('noSurfaces')}</Empty>}
      {err && <div className="text-xs text-rose-300">{err}</div>}
      {human && (
        <div className="mt-2 space-y-2">
          <div className="flex gap-2"><input className={inputCls} value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…" />
            <Button small onClick={() => input({ type: 'navigate', url })}>Go</Button></div>
          <div className="flex gap-2"><input className={inputCls} value={text} onChange={(e) => setText(e.target.value)} placeholder="text" />
            <Button small onClick={() => { input({ type: 'type', text }); setText(''); }}>Type</Button></div>
          <div className="flex gap-2 flex-wrap">
            {['Enter', 'Tab', 'Escape', 'Backspace'].map((k) => <Button small key={k} onClick={() => input({ type: 'key', key: k })}>{k}</Button>)}
            <Button small onClick={() => input({ type: 'scroll', dy: 500 })}>↓</Button>
            <Button small onClick={() => input({ type: 'scroll', dy: -500 })}>↑</Button>
          </div>
          <div className="text-[11px] text-zinc-500">Log in or complete the sensitive step yourself, then press “{t('giveBack')}”. CAPTCHAs and 2FA are never bypassed.</div>
        </div>
      )}
    </Card>
  );
}

export default function ComputerView({ ws, botId }) {
  const { t } = useT();
  const [data, setData] = useState(null);
  const [err, setErr] = useState('');
  useEffect(() => { api('/computers/surfaces').then(setData).catch((e) => setErr(e.message)); }, [ws.tick]);
  if (err) return <Empty>{err}</Empty>;
  if (!data) return <Empty>…</Empty>;
  return (
    <Section title={`${t('computer')} — ${data.kind}`}>
      <div className="text-xs text-zinc-500 mb-2">max {data.max_active_surfaces} active surfaces</div>
      {(() => {
        const list = botId ? data.surfaces.filter((s) => s.bot_id === botId) : data.surfaces;
        return list.length ? list.map((s) => <LiveSurface key={s.id} s={s} ws={ws} />) : <Empty>{t('noSurfaces')}</Empty>;
      })()}
    </Section>
  );
}
