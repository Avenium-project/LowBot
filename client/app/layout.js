import './globals.css';

export const metadata = {
  title: 'Open Dots — Open-Source Personal AI Agent Workspace',
  description: 'A self-hosted, MIT-licensed personal AI agent workspace. Explore an open-source alternative to OpenAI Dots, Meta Muse, Grok Bot, Instinct, Manus Cue, Claude Cowork, and ChatGPT agent.',
  openGraph: {
    title: 'Open Dots — Open-Source Personal AI Agent Workspace',
    description: 'Self-hostable AI chat, connectors, computer tasks, and approval-gated actions.',
    type: 'website',
  },
};

export default function RootLayout({ children }) {
  return (
    <html lang="en" className="dark" suppressHydrationWarning={true}>
      <body className="bg-background text-foreground antialiased select-none" suppressHydrationWarning={true}>
        {children}
      </body>
    </html>
  );
}
