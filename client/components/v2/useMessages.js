'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../lib/v2/api';

const PAGE_SIZE = 50;

// The latest page is fetched once. Events/reconnects advance an exclusive seq
// cursor; older pages are fetched only when the reader scrolls up.
export function useMessages(conversationId, subscribe, connection, beforePrepend, onError) {
  const [messages, setMessages] = useState([]);
  const session = useRef(null);

  useEffect(() => {
    const s = { disposed: false, initialized: false, rows: [], hasOlder: false, older: false, dirty: false, pending: null };
    session.current = s;
    setMessages([]);
    let timer;
    const base = `/conversations/${conversationId}/messages?limit=${PAGE_SIZE}`;
    const publish = () => setMessages(s.rows);
    const markRead = () => {
      const seq = s.rows.at(-1)?.seq;
      if (seq) api(`/conversations/${conversationId}/read`, { method: 'POST', body: { seq } }).catch(() => {});
    };

    s.refresh = () => {
      s.dirty = true;
      if (s.pending) return s.pending;
      s.pending = (async () => {
        try {
          while (s.dirty && !s.disposed) {
            s.dirty = false;
            const initial = !s.initialized;
            const after = s.rows.at(-1)?.seq || 0;
            const rows = await api(`${base}&${initial ? 'latest=true' : `after=${after}`}`);
            if (s.disposed) return;
            if (initial) {
              s.initialized = true;
              s.hasOlder = rows.length === PAGE_SIZE;
              s.rows = rows;
              publish();
            } else if (rows.length) {
              s.rows = s.rows.concat(rows);
              publish();
              // Catch up after reconnect without gaps, one bounded page at a time.
              if (rows.length === PAGE_SIZE) s.dirty = true;
            }
            if (rows.length) markRead();
          }
        } catch (e) {
          if (!s.disposed) onError(e.message);
        } finally { s.pending = null; }
      })();
      return s.pending;
    };

    s.loadOlder = async () => {
      if (!s.initialized || !s.hasOlder || s.older || s.disposed) return;
      s.older = true;
      try {
        const rows = await api(`${base}&before=${s.rows[0].seq}`);
        if (s.disposed) return;
        s.hasOlder = rows.length === PAGE_SIZE;
        if (rows.length) {
          beforePrepend();
          s.rows = rows.concat(s.rows);
          publish();
        }
      } catch (e) {
        if (!s.disposed) onError(e.message);
      } finally { s.older = false; }
    };

    s.refresh();
    const stop = subscribe((ev) => {
      if (ev.conversation_id !== conversationId || ev.type !== 'message.created') return;
      if (!timer) timer = setTimeout(() => { timer = null; s.refresh(); }, 100);
    });
    return () => { s.disposed = true; clearTimeout(timer); stop(); };
  }, [conversationId, subscribe, beforePrepend, onError]);

  const reload = useCallback(() => session.current?.refresh(), []);
  const loadOlder = useCallback(() => session.current?.loadOlder(), []);
  useEffect(() => { if (connection === 'live') reload(); }, [connection, reload]);
  return { messages, reload, loadOlder };
}
