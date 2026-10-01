export const metadata = {
  title: 'Open Dots — Bots',
  description: 'Persistent AI collaborators: chat, tasks, approvals, computer and routines.',
  robots: { index: false, follow: false },
  manifest: '/manifest.webmanifest',
};

export const viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  themeColor: '#09090b',
};

export default function BotsLayout({ children }) {
  return children;
}
