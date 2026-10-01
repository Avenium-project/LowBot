import Link from 'next/link';
import { notFound } from 'next/navigation';

const comparisons = {
  'openai-dots': {
    name: 'OpenAI Dots',
    title: 'Open-source alternative to OpenAI Dots',
    intro: 'Open Dots is an independently built, self-hosted AI workspace for people evaluating OpenAI Dots who want an inspectable MIT-licensed codebase and control over where their app state is stored.',
    distinction: 'Open Dots provides configurable model API access, chat, connectors, an approval and audit flow, and an optional computer runtime. It is a prototype and does not claim feature parity with OpenAI Dots or include a consumer mobile experience.',
    bestFor: 'Developers who want to run an AI assistant workspace themselves, inspect how tool actions are handled, and experiment with approval-gated computer tasks.',
    tradeoff: 'OpenAI Dots is a managed product. Open Dots requires setup, an inference API that matches its adapter contract, and your own operations. It does not currently provide a polished consumer agent experience or feature parity.',
    faq: [['Is Open Dots an open-source alternative to OpenAI Dots?', 'Yes. Open Dots is an independently built MIT-licensed project that you can run and inspect. It is an early prototype, not an official OpenAI product or a feature-equivalent replacement.'], ['Can I use Open Dots without OpenAI?', 'The app uses a configurable inference API, but the bundled adapter expects a specific prediction and upload contract. It is not a plug-and-play adapter for every OpenAI-compatible endpoint.']],
    source: ['OpenAI safety hub reference to Dots', 'https://deploymentsafety.openai.com/gpt-6-astra/evaluating-auto-review'],
  },
  'meta-muse': {
    name: 'Meta Muse',
    title: 'Open-source alternative to Meta Muse',
    intro: 'Open Dots is a self-hostable open-source AI workspace for developers and individuals comparing Meta Muse with software they can inspect and run under their own control.',
    distinction: 'Meta describes Muse as a personal agent that can work across daily apps using a dedicated secure computer. Open Dots instead offers configurable chat, connectors, explicit approvals, audit events, and an opt-in computer runtime. It does not currently offer Muse-style persistent learning, proactive goal plans, or consumer app integrations.',
    bestFor: 'People who prioritize a self-hosted codebase, explicit action approvals, and a web workspace they can adapt.',
    tradeoff: 'Muse is designed as a personal assistant product. Open Dots needs technical setup and does not provide Muse’s consumer app, proactive personal context, or persistent learning experience.',
    faq: [['Is there an open-source alternative to Meta Muse?', 'Open Dots is one self-hostable project to evaluate. It provides chat, connectors, approvals, audit events, and an optional computer runtime, but it does not reproduce Muse’s full personal-agent experience.'], ['Does Open Dots remember personal context like Muse?', 'Open Dots saves local conversation history, but it does not currently include a durable long-term memory service or proactive goal planning.']],
    source: ['Meta’s introduction to Muse', 'https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/'],
  },
  'grok-bot': {
    name: 'Grok Bot',
    title: 'Open-source alternative to Grok Bot',
    intro: 'Open Dots is an MIT-licensed, self-hostable project for people comparing Grok Bot with an AI workspace whose action pathways and code can be inspected.',
    distinction: 'Grok Bot is presented as AI teammates with their own computers that work across tools and return for approval when needed. Open Dots includes chat, a governed action gateway, approvals, audit events, connectors, and an optional computer runtime, but it does not provide always-on bots, routines, or a managed cloud computer service.',
    bestFor: 'Developers experimenting with assistant personas, limited app integrations, and approval-controlled actions in an environment they operate.',
    tradeoff: 'Grok Bot is built around managed AI teammates and their computers. Open Dots is a prototype you host yourself; it has no always-on bot service, scheduled routines, or equivalent managed computer infrastructure.',
    faq: [['Can I self-host an alternative to Grok Bot?', 'Open Dots can be run on your own infrastructure and includes an optional computer runtime. It is not a drop-in Grok Bot replacement and does not include always-on bots or scheduled routines.'], ['Does Open Dots support approval before an agent acts?', 'Higher-risk actions pass through an approval flow and produce audit events. The available actions are limited by the current action gateway and connectors.']],
    source: ['Grok Bot launch overview', 'https://www.youtube.com/watch?v=F1_0Lkp16Rc'],
  },
  instinct: {
    name: 'Instinct',
    title: 'Open-source alternative to Instinct AI',
    intro: 'Open Dots is a self-hosted, open-source AI workspace to consider if you are comparing Instinct with a project you can run and inspect yourself.',
    distinction: 'Instinct describes a personal assistant that understands what you are working on and can use a phone and computer, with messaging and calls as interaction channels. Open Dots is a web workspace with configurable assistants, connectors, explicit approvals, and an optional computer runtime. It does not currently provide SMS or phone-call access, proactive personal context, or scheduled tasks.',
    bestFor: 'People looking for a browser-based, self-hosted assistant workspace with visible approvals and an inspectable codebase.',
    tradeoff: 'Instinct emphasizes a personal assistant you can contact through messaging and calls. Open Dots does not have SMS, phone, or WhatsApp access and needs to be installed and configured by its operator.',
    faq: [['Is Open Dots an open-source Instinct alternative?', 'It is an open-source AI workspace that overlaps on assistant and computer-task use cases. It does not offer Instinct’s text or call based interface or personal context features.'], ['Can I text Open Dots to do a task?', 'No. Open Dots currently provides a web client; SMS, phone calls, and messaging-app interfaces are not implemented.']],
    source: ['Instinct official site', 'https://instinct.com/'],
  },
  'manus-cue': {
    name: 'Manus Cue',
    title: 'Open-source alternative to Manus Cue',
    intro: 'Open Dots is an open-source, self-hostable AI workspace for developers exploring an inspectable alternative alongside Manus Cue.',
    distinction: 'Cue is described as a personal-agent app from Manus. Open Dots is a prototype focused on configurable model conversations, connectors, an approval and audit gateway, and optional computer control. It does not currently offer Cue’s consumer experience or claim to complete the same breadth of delegated personal errands.',
    bestFor: 'Developers who want a small, inspectable workspace to customize and run themselves for model conversations and controlled tool use.',
    tradeoff: 'Cue is a consumer personal-agent app. Open Dots requires technical installation and does not provide Cue’s mobile or desktop experience, personal identity, or broad delegated errands.',
    faq: [['What is an open-source alternative to Manus Cue?', 'Open Dots is a self-hostable project you can evaluate if your priority is open code and control of the deployment. It is a prototype and does not match Cue’s consumer features.'], ['Does Open Dots automate personal errands like Cue?', 'Open Dots can request supported workspace, connector, and computer actions, with higher-risk actions gated for approval. It does not currently offer a broad personal-errand service.']],
    source: ['Manus 2.0 announcement mentioning Cue', 'https://www.manus.im/blog/introducing-manus-2-0'],
  },
  openclaw: {
    name: 'OpenClaw',
    title: 'Open-source alternative to OpenClaw',
    intro: 'Open Dots and OpenClaw are both open-source, self-hostable AI assistant projects. This comparison is for people evaluating different interfaces and runtime designs, rather than looking for a proprietary product replacement.',
    distinction: 'OpenClaw focuses on a personal assistant available across messaging channels, with voice, plugins, and model routing. Open Dots is a web workspace centered on assistant personas, approval-gated actions, audit events, and an optional computer runtime. It does not currently offer OpenClaw’s messaging integrations, voice features, or persistent personal assistant experience.',
    bestFor: 'Teams or developers who prefer a browser dashboard focused on auditable tool actions over a messaging-first assistant.',
    tradeoff: 'OpenClaw offers a broader personal-assistant and messaging experience. Open Dots is narrower and earlier in development; evaluate both projects’ security model, integrations, and setup requirements for your use case.',
    faq: [['How is Open Dots different from OpenClaw?', 'Both are open-source projects, but OpenClaw emphasizes messaging channels, voice, and a personal assistant. Open Dots emphasizes a web workspace, approval prompts, audit events, and a limited set of connector actions.'], ['Which should I choose for a self-hosted AI assistant?', 'Choose based on the interfaces and actions you need. OpenClaw currently offers more messaging options; Open Dots may suit experiments centered on a dashboard and explicit action approvals. Review each project before exposing it to sensitive data.']],
    source: ['OpenClaw official FAQ', 'https://docs.openclaw.ai/help/faq/what-is-openclaw'],
  },
  'claude-cowork': {
    name: 'Claude Cowork',
    title: 'Open-source alternative to Claude Cowork',
    intro: 'Open Dots is an open-source, self-hostable AI workspace for people evaluating Claude Cowork and looking for a codebase they can inspect and adapt.',
    distinction: 'Claude Cowork can work with files and use a computer to complete tasks in Anthropic’s product experience. Open Dots offers configurable chat, narrow connectors, approval-gated actions, audit events, and an optional Docker/Playwright computer runtime. It is not feature-equivalent to Cowork and lacks its polished desktop, cloud, and mobile experiences.',
    bestFor: 'Developers who want to experiment with a self-hosted assistant interface and inspect the code behind its supported actions.',
    tradeoff: 'Claude Cowork provides a finished product experience for delegated computer and file tasks. Open Dots needs installation, provider configuration, and operational care; its computer runtime is optional and not hardened for hostile websites.',
    faq: [['Can Open Dots replace Claude Cowork?', 'Not feature for feature. Open Dots is an early open-source workspace with chat, selected connectors, approvals, audit events, and optional computer control. It does not have Cowork’s managed product experience.'], ['Does Open Dots run computer tasks locally?', 'The project includes an optional Docker/Playwright computer provider and a compatible remote provider. The default provider is a development adapter, and the runtime is not a hardened security boundary for arbitrary web content.']],
    source: ['Claude Help: computer use in Cowork', 'https://support.claude.com/en/articles/14128542-let-claude-use-your-computer-in-cowork'],
  },
  'chatgpt-agent': {
    name: 'ChatGPT agent',
    title: 'Open-source alternative to ChatGPT agent',
    intro: 'Open Dots is a self-hostable open-source AI workspace for developers comparing ChatGPT agent with an implementation they can inspect and run themselves.',
    distinction: 'ChatGPT agent combines research and action through a virtual computer and supported connectors. Open Dots provides a configurable model API, chat, limited app connectors, explicit approvals, audit events, and optional computer control. It does not include OpenAI’s managed agent runtime, broad connector catalog, or equivalent end-to-end task capabilities.',
    bestFor: 'Developers who want to study and adapt a self-hosted assistant app with an explicit action gateway.',
    tradeoff: 'ChatGPT agent is a managed service for researching and completing tasks. Open Dots is a prototype requiring self-hosting and a compatible inference API; it does not provide the same breadth of connectors or managed computer environment.',
    faq: [['Is there an open-source ChatGPT agent alternative?', 'Open Dots is an open-source project for people who want to inspect and self-host an AI workspace. It has a narrower set of tools and is not a feature-equivalent version of ChatGPT agent.'], ['Does Open Dots use OpenAI models?', 'The app’s inference adapter can be configured with an API key, base URL, and model ID for a service matching its request contract. Compatibility with a provider depends on that service; the adapter is not a universal OpenAI-compatible client.']],
    source: ['OpenAI: Introducing ChatGPT agent', 'https://openai.com/index/introducing-chatgpt-agent/'],
  },
};

export function generateStaticParams() {
  return Object.keys(comparisons).map((competitor) => ({ competitor }));
}

export async function generateMetadata({ params }) {
  const { competitor } = await params;
  const comparison = comparisons[competitor];
  if (!comparison) return {};
  return {
    title: `${comparison.title} | Open Dots`,
    description: `${comparison.intro} See current capabilities and limitations.`,
    openGraph: { title: `${comparison.title} | Open Dots`, description: comparison.intro, type: 'article' },
  };
}

export default async function AlternativePage({ params }) {
  const { competitor } = await params;
  const comparison = comparisons[competitor];
  if (!comparison) notFound();

  return (
    <main className="min-h-screen bg-[#09090b] text-zinc-100">
      <header className="mx-auto flex max-w-4xl items-center justify-between px-6 py-6">
        <Link href="/" className="font-semibold">Open Dots</Link>
        <Link href="/app" className="rounded-lg bg-white px-4 py-2 text-sm font-medium text-zinc-950">Open the app</Link>
      </header>
      <article className="mx-auto max-w-4xl px-6 pb-20 pt-12">
        <nav className="mb-8 text-sm text-zinc-500"><Link href="/">Home</Link> / Alternatives / {comparison.name}</nav>
        <p className="text-sm font-medium uppercase tracking-[0.18em] text-violet-300">Product comparison</p>
        <h1 className="mt-4 text-4xl font-semibold tracking-tight md:text-5xl">{comparison.title}</h1>
        <p className="mt-6 text-lg leading-8 text-zinc-300">{comparison.intro}</p>
        <section className="mt-10 rounded-2xl border border-zinc-800 bg-zinc-950 p-6 md:p-8">
          <h2 className="text-xl font-semibold">How Open Dots compares</h2>
          <p className="mt-4 leading-7 text-zinc-300">{comparison.distinction}</p>
          <p className="mt-5 text-sm text-zinc-500">Product information: <a className="underline hover:text-white" href={comparison.source[1]} target="_blank" rel="noreferrer">{comparison.source[0]}</a>.</p>
        </section>
        <section className="mt-8 grid gap-4 md:grid-cols-2">
          <div className="rounded-xl border border-zinc-800 p-5"><h2 className="font-semibold">Who Open Dots is for</h2><p className="mt-3 leading-7 text-zinc-300">{comparison.bestFor}</p></div>
          <div className="rounded-xl border border-zinc-800 p-5"><h2 className="font-semibold">Trade-offs to consider</h2><p className="mt-3 leading-7 text-zinc-300">{comparison.tradeoff}</p></div>
        </section>
        <section className="mt-8">
          <h2 className="text-2xl font-semibold">Open Dots features</h2>
          <ul className="mt-4 list-inside list-disc space-y-2 leading-7 text-zinc-300">
            <li>Self-hostable web client and API; app state is stored in local SQLite.</li>
            <li>Assistant personas, model selection, conversation history, and image attachments.</li>
            <li>Composio connectors with narrow GitHub issue actions.</li>
            <li>Deny-by-default action gateway, approval prompts, and audit events for higher-risk actions.</li>
            <li>Optional bot-scoped Docker/Playwright computer runtime or compatible remote service.</li>
          </ul>
        </section>
        <aside className="mt-8 border-l-2 border-amber-500 pl-5 text-sm leading-6 text-zinc-400">
          Open Dots is a prototype in active development. It is single-owner, lacks long-term memory, scheduled routines, and a mobile client, and its computer runtime is not a hardened boundary for hostile websites. Review the README before deployment.
        </aside>
        <section className="mt-10">
          <h2 className="text-2xl font-semibold">Frequently asked questions</h2>
          <div className="mt-4 space-y-4">{comparison.faq.map(([question, answer]) => <article key={question} className="rounded-xl border border-zinc-800 p-5"><h3 className="font-semibold">{question}</h3><p className="mt-2 leading-7 text-zinc-300">{answer}</p></article>)}</div>
        </section>
        <div className="mt-10 flex flex-wrap gap-4">
          <Link href="/app" className="rounded-lg bg-violet-500 px-5 py-3 font-medium hover:bg-violet-400">Try Open Dots</Link>
          <a href="https://github.com/Anil-matcha/open-dots" className="rounded-lg border border-zinc-700 px-5 py-3 font-medium hover:bg-zinc-900">Read the source</a>
        </div>
        <p className="mt-8 text-xs leading-5 text-zinc-600">Open Dots is an independent project and is not affiliated with or endorsed by {comparison.name} or its owners. Comparison reflects public product descriptions and the current Open Dots README; capabilities can change.</p>
      </article>
    </main>
  );
}
