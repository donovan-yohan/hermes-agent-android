import { createRoot } from 'react-dom/client'
import { type ReactNode } from 'react'
import { atom, host } from '@hermes/plugin-sdk'
import { I18nProvider } from '@/i18n'
import { registerPluginLocales } from '@/i18n/plugin-i18n'
import { ThemeProvider } from '@/themes/context'
import { $botMeta, $lastRoster } from '@/plugins/hermes-bots/data'
import { $groupChats } from '@/plugins/hermes-bots/group-chat'
import { openGroupChat } from '@/plugins/hermes-bots/group-chat-view'
import { BOTS_LOCALES } from '@/plugins/hermes-bots/i18n'
import { $previewTabs } from '@/store/preview'
import '@/styles.css'

const sha = '36922ad064d65dcf25f8f48df81e1ccf9a55de67'
const url = 'https://github.com/donovan-yohan/hermes-agent-android/actions/runs/39120359182/artifacts/12134162983'
const exactSource = `[download the latest apk](${url})`
const fixtures = [
  { id: 'labeled-apk', text: exactSource },
  { id: 'bare-apk', text: url },
  { id: 'rich-markdown', text: `## APK link checks\n\n- Labeled: ${exactSource}\n- Bare: ${url}\n\nInline literal: \`${url}\`\n\n\`\`\`kotlin\nval apkUrl = "${url}"\n\`\`\`` },
  { id: 'unsafe-inert', text: '[unsafe javascript](javascript:alert(1))\n\n[unsafe data](data:text/html,unsafe)' }
]
const group = 'APK link fixture'
const at = Date.parse('2026-10-02T12:00:00Z')
registerPluginLocales('hermes-bots', BOTS_LOCALES)
$lastRoster.set([{ name: 'builder', title: 'Builder' }] as never)
$botMeta.set({ builder: { groups: [group], title: 'Builder' } } as never)
$groupChats.set({ [group]: {
  roomId: 'public-apk-link-fixture', running: false, epoch: 1, members: [{ name: 'builder' }],
  sessions: {}, watermarks: {},
  log: fixtures.map((fixture, index) => ({ id: fixture.id, thread: 'apk-thread', from: { kind: 'member', name: 'builder' }, text: fixture.text, at: at + index }))
} } as never)

// Only window-host seams are replaced, not group/message/theme components.
// openGroupChat supplies its own private GroupChatMainView render callback.
let render: (() => ReactNode) | undefined
let workspaceId: string | undefined
host.openWorkspace = ((id: string, options: { render: () => ReactNode }) => {
  workspaceId = id
  render = options.render
  return () => {}
}) as typeof host.openWorkspace
host.paneVisibility = (() => atom(true)) as typeof host.paneVisibility
openGroupChat(group)
if (!render) throw new Error('Production openGroupChat did not publish GroupChatMainView')

;(window as any).__fixture = {
  sha, url, exactSource, fixtures, group, workspaceId,
  kind: 'production GroupChatMainView via openGroupChat callback; synthetic public room data; fixture host window seams only',
  seams: ['host.openWorkspace captures production render callback', 'host.paneVisibility always visible'],
  previewTargets: () => $previewTabs.get().map(tab => ({ id: tab.id, target: tab.target }))
}

createRoot(document.getElementById('root')!).render(
  <I18nProvider initialLocale="en" configClient={null}><ThemeProvider>
    <main data-fixture-sha={sha} style={{ width: '100%', height: '100vh', display: 'flex', flexDirection: 'column' }}>
      <header style={{ padding: '8px 12px', fontSize: 11, borderBottom: '1px solid var(--ui-stroke-secondary)' }}>
        Public synthetic room · production GroupChatMainView · {sha.slice(0, 7)}
      </header>
      <section id="production-group" style={{ flex: 1, minHeight: 0 }}>{render()}</section>
    </main>
  </ThemeProvider></I18nProvider>
)
