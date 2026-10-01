'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api, flushOutbox, subscribeEvents } from '../../lib/v2/api';

// Server state is the source of truth. Events only tell us what to refetch,
// so a missed or duplicated event can never corrupt client state.
export function useWorkspace(enabled) {
  const [bots, setBots] = useState([]);
  const [conversations, setConversations] = useState([]);
  const [approvals, setApprovals] = useState([]);
  const [elicitations, setElicitations] = useState([]);
  const [notifications, setNotifications] = useState({ items: [], unread: 0 });
  const [tasks, setTasks] = useState([]);
  const [connection, setConnection] = useState('connecting');
  const [health, setHealth] = useState(null);
  const [tick, setTick] = useState(0); // bumps on any event -> panels refetch
  const listeners = useRef(new Set());
  const dirty = useRef(new Set());
  const timer = useRef(null);

  const load = useCallback(async (what) => {
    const all = !what;
    const jobs = [];
    if (all || what.has('bots')) jobs.push(api('/bots').then(setBots));
    if (all || what.has('conversations')) jobs.push(api('/conversations').then(setConversations));
    if (all || what.has('approvals')) jobs.push(api('/approvals').then(setApprovals));
    if (all || what.has('notifications')) jobs.push(api('/notifications').then(setNotifications));
    if (all || what.has('tasks')) jobs.push(api('/tasks?limit=100').then(setTasks));
    if (all || what.has('elicitations')) jobs.push(api('/mcp/elicitations').then(setElicitations).catch(() => setElicitations([])));
    if (all) jobs.push(api('/health').then(setHealth).catch(() => {}));
    await Promise.allSettled(jobs);
  }, []);

  const onEvent = useCallback((ev) => {
    const t = ev.type;
    const d = dirty.current;
    if (t.startsWith('run.') || t.startsWith('task.') || t.startsWith('bot.')) { d.add('bots'); d.add('tasks'); }
    if (t.startsWith('message.') || t.startsWith('conversation.')) d.add('conversations');
    if (t.startsWith('approval.')) { d.add('approvals'); d.add('tasks'); }
    if (t.startsWith('notification.')) d.add('notifications');
    if (t.startsWith('elicitation.')) d.add('elicitations');
    listeners.current.forEach((fn) => fn(ev));
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      const what = new Set(dirty.current);
      dirty.current.clear();
      load(what);
      setTick((x) => x + 1);
    }, 150);
  }, [load]);

  useEffect(() => {
    if (!enabled) return undefined;
    load();
    flushOutbox().catch(() => {});
    const stop = subscribeEvents(onEvent, (s) => {
      setConnection(s);
      if (s === 'live') load(); // resync after reconnect
    });
    const online = () => flushOutbox().catch(() => {});
    window.addEventListener('online', online);
    return () => { stop(); window.removeEventListener('online', online); };
  }, [enabled, load, onEvent]);

  const subscribe = useCallback((fn) => { listeners.current.add(fn); return () => listeners.current.delete(fn); }, []);

  return { bots, conversations, approvals, elicitations, notifications, tasks, connection, health, tick, reload: load, subscribe };
}
