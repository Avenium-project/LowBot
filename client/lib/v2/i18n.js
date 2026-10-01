'use client';
import { createContext, useContext } from 'react';

export const DICT = {
  pl: {
    chats: 'Rozmowy', tasks: 'Zadania', inbox: 'Skrzynka', computer: 'Komputer', more: 'Więcej',
    bots: 'Boty', groups: 'Grupy', newBot: 'Nowy bot', newGroup: 'Nowa grupa', search: 'Szukaj',
    send: 'Wyślij', typeMessage: 'Napisz wiadomość… (@bot, /skill)', approve: 'Zatwierdź', deny: 'Odrzuć',
    edit: 'Edytuj', save: 'Zapisz', cancel: 'Anuluj', stop: 'Stop', pause: 'Pauza', resume: 'Wznów',
    takeOver: 'Przejmij sterowanie', giveBack: 'Oddaj sterowanie', approvals: 'Zgody', notifications: 'Powiadomienia',
    routines: 'Rutyny', memory: 'Pamięć', skills: 'Skille', settings: 'Ustawienia', models: 'Modele',
    devices: 'Urządzenia', mcp: 'MCP', policies: 'Uprawnienia', backup: 'Kopia zapasowa', language: 'Język',
    status_idle: 'śpi', status_working: 'pracuje', status_queued: 'w kolejce', status_needs_approval: 'czeka na zgodę',
    status_needs_input: 'czeka na odpowiedź', status_waiting: 'czeka na innego bota', status_retrying: 'ponawia',
    status_paused: 'wstrzymany', status_needs_resolution: 'wymaga decyzji',
    connection_live: 'połączono', connection_offline: 'offline — ponawiam', connection_connecting: 'łączenie…',
    empty: 'Brak elementów', name: 'Nazwa', role: 'Rola', instructions: 'Instrukcje', model: 'Model', provider: 'Dostawca',
    create: 'Utwórz', delete: 'Usuń', duplicate: 'Duplikuj', hide: 'Ukryj', unhide: 'Pokaż', pin: 'Przypnij', unpin: 'Odepnij',
    testConnection: 'Testuj połączenie', schedule: 'Harmonogram', prompt: 'Polecenie', nextRuns: 'Najbliższe uruchomienia',
    simulate: 'Symulacja (bez zapisu)', testRun: 'Test run (prawdziwy)', history: 'Historia', enabled: 'włączona', disabled: 'wyłączona',
    answer: 'Odpowiedz', waitingFor: 'Czeka na', result: 'Wynik', error: 'Błąd', steps: 'Kroki', subtasks: 'Podzadania',
    pairDevice: 'Sparuj urządzenie', pairingCode: 'Kod parowania', revoke: 'Odwołaj', serverUrl: 'Adres serwera',
    ownerToken: 'Token właściciela', signIn: 'Zaloguj', setupTitle: 'Konfiguracja Open Dots', next: 'Dalej', back: 'Wstecz',
    firstBot: 'Pierwszy bot', done: 'Gotowe', liveView: 'Podgląd na żywo', noSurfaces: 'Brak otwartych ekranów. Bot otworzy przeglądarkę, gdy będzie jej potrzebował.',
    controlledBy: 'Steruje', unknownOutcome: 'Nie wiadomo, czy akcja się wykonała', markDone: 'Wykonało się', markNotDone: 'Nie wykonało się — ponów', abandon: 'Porzuć',
    dictate: 'Dyktuj', voiceNote: 'Notatka głosowa', attach: 'Załącz', download: 'Pobierz', files: 'Pliki',
    effect: 'Skutek', target: 'Cel', parameters: 'Parametry', expires: 'Wygasa', markAllRead: 'Oznacz wszystkie jako przeczytane',
    hierarchy: 'Hierarchia (CEO → Head → Manager → Worker)', reportsTo: 'Raportuje do', orgRole: 'Rola w strukturze',
    computerMode: 'Tryb komputera', shared: 'wspólny', isolated: 'izolowany', budget: 'Budżet / dzień', tools: 'Narzędzia',
    pause_bot: 'Wstrzymaj bota', resume_bot: 'Wznów bota', export: 'Eksport', mockWarning: 'Dostawca testowy (atrapa) — to nie jest prawdziwy model.',
    you: 'Ty', menu: 'Menu', emptyBots: 'Nie masz jeszcze botów. Dotknij +, aby utworzyć pierwszego.', waitingApprovals: 'czeka na Twoją zgodę', newChoice: 'Co chcesz utworzyć?', profile: 'Profil',
    elicitation: 'Pytanie z narzędzia MCP', openLink: 'Otwórz link', domain: 'Domena', accept: 'Akceptuj', decline: 'Odmów',
  },
  en: {
    chats: 'Chats', tasks: 'Tasks', inbox: 'Inbox', computer: 'Computer', more: 'More',
    bots: 'Bots', groups: 'Groups', newBot: 'New bot', newGroup: 'New group', search: 'Search',
    send: 'Send', typeMessage: 'Message… (@bot, /skill)', approve: 'Approve', deny: 'Deny',
    edit: 'Edit', save: 'Save', cancel: 'Cancel', stop: 'Stop', pause: 'Pause', resume: 'Resume',
    takeOver: 'Take over', giveBack: 'Resume bot', approvals: 'Approvals', notifications: 'Notifications',
    routines: 'Routines', memory: 'Memory', skills: 'Skills', settings: 'Settings', models: 'Models',
    devices: 'Devices', mcp: 'MCP', policies: 'Permissions', backup: 'Backup', language: 'Language',
    status_idle: 'asleep', status_working: 'working', status_queued: 'queued', status_needs_approval: 'needs approval',
    status_needs_input: 'needs your answer', status_waiting: 'waiting on a bot', status_retrying: 'retrying',
    status_paused: 'paused', status_needs_resolution: 'needs a decision',
    connection_live: 'connected', connection_offline: 'offline — retrying', connection_connecting: 'connecting…',
    empty: 'Nothing here', name: 'Name', role: 'Role', instructions: 'Instructions', model: 'Model', provider: 'Provider',
    create: 'Create', delete: 'Delete', duplicate: 'Duplicate', hide: 'Hide', unhide: 'Unhide', pin: 'Pin', unpin: 'Unpin',
    testConnection: 'Test connection', schedule: 'Schedule', prompt: 'Prompt', nextRuns: 'Next runs',
    simulate: 'Simulate (no writes)', testRun: 'Test run (real)', history: 'History', enabled: 'enabled', disabled: 'disabled',
    answer: 'Answer', waitingFor: 'Waiting for', result: 'Result', error: 'Error', steps: 'Steps', subtasks: 'Subtasks',
    pairDevice: 'Pair a device', pairingCode: 'Pairing code', revoke: 'Revoke', serverUrl: 'Server URL',
    ownerToken: 'Owner token', signIn: 'Sign in', setupTitle: 'Set up Open Dots', next: 'Next', back: 'Back',
    firstBot: 'First bot', done: 'Done', liveView: 'Live view', noSurfaces: 'No open screens. A bot opens a browser when it needs one.',
    controlledBy: 'Controlled by', unknownOutcome: 'Unknown whether the action happened', markDone: 'It happened', markNotDone: 'It did not — retry', abandon: 'Abandon',
    dictate: 'Dictate', voiceNote: 'Voice note', attach: 'Attach', download: 'Download', files: 'Files',
    effect: 'Effect', target: 'Target', parameters: 'Parameters', expires: 'Expires', markAllRead: 'Mark all read',
    hierarchy: 'Hierarchy (CEO → Head → Manager → Worker)', reportsTo: 'Reports to', orgRole: 'Org role',
    computerMode: 'Computer mode', shared: 'shared', isolated: 'isolated', budget: 'Budget / day', tools: 'Tools',
    pause_bot: 'Pause bot', resume_bot: 'Resume bot', export: 'Export', mockWarning: 'Test (mock) provider — not a real model.',
    you: 'You', menu: 'Menu', emptyBots: 'No bots yet. Tap + to create your first one.', waitingApprovals: 'awaiting your approval', newChoice: 'What do you want to create?', profile: 'Profile',
    elicitation: 'Question from an MCP tool', openLink: 'Open link', domain: 'Domain', accept: 'Accept', decline: 'Decline',
  },
};

export const LangContext = createContext({ lang: 'pl', t: (k) => DICT.pl[k] || k, setLang: () => {} });
export const useT = () => useContext(LangContext);

export function detectLang() {
  if (typeof window === 'undefined') return 'pl';
  const saved = window.localStorage.getItem('opendots.lang');
  if (saved === 'pl' || saved === 'en') return saved;
  return (navigator.language || 'pl').toLowerCase().startsWith('pl') ? 'pl' : 'en';
}
