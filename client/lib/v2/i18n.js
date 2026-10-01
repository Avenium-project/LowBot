'use client';
import { createContext, useContext } from 'react';

export const DICT = {
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
    ownerToken: 'Owner token', signIn: 'Sign in', setupTitle: 'Set up LowBot', next: 'Next', back: 'Back',
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

export const LangContext = createContext({ lang: 'en', t: (k) => DICT.en[k] || k, setLang: () => {} });
export const useT = () => useContext(LangContext);

export function detectLang() {
  return 'en'; // LowBot ships in English only
}
