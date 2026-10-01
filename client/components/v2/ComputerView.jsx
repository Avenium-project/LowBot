'use client';
import { useEffect, useRef, useState } from 'react';
import { api, fetchBlobUrl, isLocal } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Empty, Section, botLabel, inputCls } from './ui';

function LiveSurface({ s, ws }) {
  const { t } = useT();
  const [img, setImg] = useState(null);
  const [err, setErr] = useState('');
  const [text, setText] = useState('');
  const [url, setUrl] = useState('');
  const imgRef = useRef(null);
  const human = s.controller.startsWith('human:');
  const bot = ws.bots.find((b) => b.id === s.bot_id);
  const local = isLocal();
  const { lang } = useT();
  const [skillName, setSkillName] = useState('');
  const [taught, setTaught] = useState('');
  const teach = async () => {
    try {
      const sk = await api('/skills/teach', { method: 'POST', body: { bot_id: s.bot_id, name: skillName || 'Taught task', consent: true } });
      setTaught(`/${sk.slug}`);
    } catch (e) { setErr(e.message); }
  };

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
        <div className="text-[15px]">
          <b>{botLabel(bot)}</b> · {s.mode} · <span className={s.live ? 'text-emerald-400' : 'text-zinc-500'}>{s.live ? '● live' : '○ closed'}</span>
          <div className="text-[13px] text-zinc-400 break-all">{s.url}</div>
          <div className="text-[13px]">{t('controlledBy')}: <b className={human ? 'text-amber-400' : 'text-sky-400'}>{human ? 'you' : bot?.name}</b>{s.busy ? ' · acting…' : ''}</div>
        </div>
        {human
          ? <Button small kind="primary" onClick={() => api(`/computers/surfaces/${s.id}/resume`, { method: 'POST' }).then(() => ws.reload())}>{t('giveBack')}</Button>
          : <Button small kind="danger" onClick={() => api(`/computers/surfaces/${s.id}/takeover`, { method: 'POST' }).then(() => ws.reload())}>{t('takeOver')}</Button>}
      </div>
      {img ? <img ref={imgRef} src={img} alt={t('liveView')} onClick={click}
        className={`w-full rounded-2xl ${human ? 'cursor-crosshair' : ''}`} /> : <Empty>{s.live ? '…' : t('noSurfaces')}</Empty>}
      {err && <div className="text-[13px] text-rose-400">{err}</div>}
      {local && !human && s.recorded_steps > 0 && (
        <div className="mt-2 space-y-2 rounded-2xl bg-[#2a2a2a] p-3">
          <div className="text-[15px]">{`${s.recorded_steps} steps recorded. Turn them into a draft skill:`}</div>
          <div className="flex gap-2"><input className={inputCls} value={skillName} onChange={(e) => setSkillName(e.target.value)} placeholder={'Skill name'} />
            <Button small kind="primary" onClick={teach}>{'Teach'}</Button></div>
          {taught && <div className="text-[13px] text-emerald-400">{'Created'} {taught}</div>}
        </div>
      )}
      {human && !local && (
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
          <div className="text-[12px] text-zinc-500">Log in or complete the sensitive step yourself, then press “{t('giveBack')}”. CAPTCHAs and 2FA are never bypassed.</div>
        </div>
      )}
    </Card>
  );
}

export default function ComputerView({ ws, botId }) {
  const { t, lang } = useT();
  const local = isLocal();
  const [data, setData] = useState(null);
  const [err, setErr] = useState('');
  useEffect(() => { api('/computers/surfaces').then(setData).catch((e) => setErr(e.message)); }, [ws.tick]);
  if (err) return <Empty>{err}</Empty>;
  if (!data) return <Empty>…</Empty>;
  const openOwn = () => api('/computers/open', { method: 'POST', body: { bot_id: botId } }).then(() => ws.reload()).catch((e) => setErr(e.message));
  return (
    <Section title={`${t('computer')} — ${local ? ('this phone') : data.kind}`}>
      <div className="text-[13px] text-zinc-500 mb-2">{local
        ? ('Bots use a browser inside this phone (shared cookies). Take over shows the real page: sign in yourself, then Give back control. Tick Record to teach a task.')
        : `max ${data.max_active_surfaces} active surfaces`}</div>
      {local && botId && <Button kind="primary" onClick={openOwn}>{"Open the bot's browser"}</Button>}
      {(() => {
        const list = botId ? data.surfaces.filter((s) => s.bot_id === botId) : data.surfaces;
        return list.length ? list.map((s) => <LiveSurface key={s.id} s={s} ws={ws} />) : <Empty>{t('noSurfaces')}</Empty>;
      })()}
    </Section>
  );
}
