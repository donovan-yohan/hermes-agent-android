import { test, expect } from '@playwright/test'
import { setupMockBackend, waitForAppReady, buildAppEnv } from './fixtures'
import { execFileSync } from 'node:child_process'
import * as path from 'node:path'
import * as fs from 'node:fs'

// Disposable capture-only protocol fixture; untouched built production renderer.
test('sidebar synthetic production journey', async () => {
  test.setTimeout(180_000)
  const fixture = await setupMockBackend({ extraConfig: 'auxiliary:\n  title_generation:\n    enabled: false' })
  const trace: unknown[] = []
  const clock = 1789654800
  const sessions = [
    { id: 'synthetic-design', title: 'Synthetic design review', last_active: clock - 720 },
    { id: 'synthetic-release', title: 'Synthetic release checklist', last_active: clock - 1200 },
  ].map(row => ({ ...row, profile: 'default', preview: 'Synthetic capture session', cwd: '/synthetic/project', source: 'cli', message_count: 0, archived: false }))
  const project = {
    id: 'synthetic-project', label: 'Synthetic project', name: 'Synthetic project', path: '/synthetic/project',
    profile: 'default', sessionCount: 2, lastActive: clock - 720, sessionIds: sessions.map(s => s.id),
    previewSessions: sessions, repos: [{ id: 'synthetic-repo', name: 'project', path: '/synthetic/project', sessionCount: 2, groups: [{ id: 'synthetic-lane', label: 'project', path: '/synthetic/project', sessionCount: 2, sessions }] }],
  }
  try {
    await waitForAppReady(fixture, 60_000)
    const repo = path.resolve(import.meta.dirname, '../../..')
    execFileSync(path.join(repo, '.venv/bin/python'), ['-c', `
import json, sys, sqlite3
from pathlib import Path
from hermes_state import SessionDB
home = Path(sys.argv[1])
assert home.name == 'hermes-home' and home.parent.name.startswith('hermes-e2e-')
db = SessionDB(home / 'state.db')
for row in json.loads(sys.argv[2]):
    db.create_session(row['id'], 'cli', cwd=row['cwd'], profile_name='default')
    db.set_session_title(row['id'], row['title'])
    db.append_message(row['id'], 'user', 'Synthetic capture session', timestamp=row['last_active'])
db.close()
with sqlite3.connect(home / 'state.db') as conn:
    for row in json.loads(sys.argv[2]):
        conn.execute('UPDATE sessions SET started_at=?, last_activity_at=? WHERE id=?', (row['last_active'], row['last_active'], row['id']))
`, fixture.sandbox.hermesHome, JSON.stringify(sessions)], { cwd: repo, env: buildAppEnv(fixture.sandbox) })
    await fixture.page.routeWebSocket(/.*/, ws => {
      const server = ws.connectToServer()
      ws.onMessage(raw => {
        let msg: any
        try { msg = JSON.parse(String(raw)) } catch { server.send(raw); return }
        trace.push({ method: msg.method, params: msg.params })
        let result: unknown
        switch (msg.method) {
          case 'session.list': result = { sessions }; break
          case 'projects.tree': result = { projects: [project], active_id: project.id, scoped_session_ids: sessions.map(s => s.id) }; break
          case 'projects.list': result = { projects: [project], active_id: project.id }; break
          case 'projects.project_sessions': result = { project }; break
          default: server.send(raw); return
        }
        ws.send(JSON.stringify({ jsonrpc: '2.0', id: msg.id, result }))
      })
      server.onMessage(raw => ws.send(raw))
    })
    await fixture.page.addInitScript(({ clock }) => {
      const OriginalDate = Date
      class FixedDate extends OriginalDate {
        constructor(...args: any[]) { super(...(args.length ? args : [clock * 1000]) as [any]) }
        static now() { return clock * 1000 }
      }
      window.Date = FixedDate as DateConstructor
      localStorage.setItem('hermes.desktop.agentsGroupedByWorkspace', 'true')
      localStorage.setItem('hermes-desktop-theme-v2', 'mono')
      localStorage.setItem('hermes-desktop-mode-v1', 'dark')
    }, { clock })
    const cdp = await fixture.page.context().newCDPSession(fixture.page)
    await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'UTC' })
    await cdp.send('Emulation.setLocaleOverride', { locale: 'en-US' })
    await fixture.page.reload()
    await waitForAppReady(fixture, 60_000)
    await fixture.page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' })
    await expect(fixture.page.getByText('Synthetic project', { exact: true }).first()).toBeVisible({ timeout: 30_000 })
    await expect(fixture.page.locator('html')).toHaveAttribute('data-hermes-theme', 'mono')
    await expect(fixture.page.locator('html')).toHaveAttribute('data-hermes-mode', 'dark')
    const capture = async (state: string) => {
      await fixture.page.screenshot({ path: `test-results/${state}-dark.png`, animations: 'disabled' })
      fs.writeFileSync(`test-results/${state}-desktop-runtime.json`, JSON.stringify(await fixture.page.evaluate(() => ({
        skin: document.documentElement.getAttribute('data-hermes-theme'),
        mode: document.documentElement.getAttribute('data-hermes-mode'),
        locale: navigator.language, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        now: Date.now(), text: document.querySelector('[data-sessions-mode]')?.textContent,
      })), null, 2))
    }
    await fixture.page.getByText('Synthetic project', { exact: true }).first().click()
    await expect(fixture.page.getByText('Synthetic release checklist', { exact: true }).first()).toBeVisible()
    await fixture.page.getByRole('button', { name: /All projects/ }).click()
    await fixture.page.getByRole('textbox', { name: 'Search sessions' }).fill('design')
    await expect(fixture.page.getByText('Synthetic design review', { exact: true }).first()).toBeVisible()
    await fixture.page.getByRole('textbox', { name: 'Search sessions' }).fill('zqxvparitynomatch')
    const sidebar = fixture.page.locator('[data-sessions-mode]')
    await expect(sidebar.getByText('Synthetic design review', { exact: true })).toHaveCount(0)
    await expect(sidebar.getByText('Synthetic release checklist', { exact: true })).toHaveCount(0)
    await expect(sidebar).toContainText('No sessions match “zqxvparitynomatch”.')
    await capture('projection-search-miss')
    await fixture.page.getByRole('textbox', { name: 'Search sessions' }).fill('')
    await expect(sidebar.getByText('Synthetic design review', { exact: true })).toBeVisible()
    const composer = fixture.page.locator('[contenteditable="true"]').first()
    await expect(composer).toBeVisible()
    await expect(composer).toHaveText('')
    const edits = []
    const before = await sidebar.textContent()
    for (let edit = 0; edit < 20; edit++) {
      const text = `Synthetic draft ${edit}`
      await composer.fill(text)
      await expect(composer).toHaveText(text)
      await expect(sidebar).toHaveText(before!)
      edits.push({ edit, observed: await composer.textContent(), sidebar: await sidebar.textContent() })
    }
    fs.writeFileSync('test-results/sidebar-draft-observed.json', JSON.stringify({ initialDraft: '', edits }, null, 2))
    await capture('projection-draft-reuse')
    fs.writeFileSync('test-results/sidebar-synthetic-rpc-trace.json', JSON.stringify(trace, null, 2))
  } finally {
    fs.writeFileSync('test-results/sidebar-synthetic-rpc-trace.json', JSON.stringify(trace, null, 2))
    await fixture.cleanup()
  }
})
