import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createServer } from 'vite'
import { chromium } from '@playwright/test'

const root = path.resolve(import.meta.dirname, '../..')
const repo = path.resolve(root, '../..')
const fixtureDir = path.join(root, 'ignored/group-message-links')
const out = process.env.DESKTOP_LINKS_OUT || path.join(repo, 'build/visual-parity/group-message-links/desktop')
const sha = '36922ad064d65dcf25f8f48df81e1ccf9a55de67'
const origin = 'http://127.0.0.1:52762'
const url = 'https://github.com/donovan-yohan/hermes-agent-android/actions/runs/39120359182/artifacts/12134162983'
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).trim()
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const shellQuote = value => "'" + value.replaceAll("'", "'\\''") + "'"
assert.equal(git('rev-parse', 'HEAD'), sha)
assert.equal(git('diff', sha, '--', 'apps/desktop/src', 'apps/desktop/vite.config.ts', 'packages'), '')
fs.mkdirSync(out, { recursive: true })
// Explicit ignored cache avoids writing through reused node_modules symlinks.
const server = await createServer({ root, configFile: path.join(root, 'vite.config.ts'), configLoader: 'runner', envDir: false,
  cacheDir: path.join(fixtureDir, 'node_modules/.vite'),
  server: { host: '127.0.0.1', port: 52762, strictPort: true, open: false, hmr: false, watch: null },
  optimizeDeps: { entries: ['ignored/group-message-links/index.html'] }
})
let browser
const errors = [], consoleErrors = [], blocked = [], requests = [], states = [], taps = [], screenshots = []
const startedAtUtc = new Date().toISOString()
const deadline = setTimeout(() => { console.error('capture deadline reached'); process.exit(124) }, 240000)
try {
  await server.listen()
  assert.equal(server.config.envDir, false)
  assert.equal(server.config.cacheDir, path.join(fixtureDir, 'node_modules/.vite'))
  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--disable-background-networking', '--disable-component-update'] })
  for (const mode of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width: 412, height: 1100 }, deviceScaleFactor: 1, hasTouch: true,
      locale: 'en-US', timezoneId: 'UTC', reducedMotion: 'reduce', colorScheme: mode, serviceWorkers: 'block' })
    await context.route('**/*', route => {
      const href = route.request().url(); requests.push(href)
      if (new URL(href).origin === origin) return route.continue()
      blocked.push(href); return route.abort('blockedbyclient')
    })
    await context.addInitScript(({ mode }) => {
      localStorage.setItem('hermes-desktop-theme-v2', 'mono')
      localStorage.setItem('hermes-desktop-mode-v1', mode)
    }, { mode })
    const page = await context.newPage()
    await page.clock.setFixedTime(new Date('2026-10-02T12:00:00Z'))
    page.on('pageerror', error => errors.push({ mode, message: error.message }))
    page.on('console', message => { if (message.type() === 'error') consoleErrors.push({ mode, message: message.text() }) })
    await page.goto(`${origin}/ignored/group-message-links/index.html`, { waitUntil: 'domcontentloaded', timeout: 120000 })
    try {
      await page.waitForSelector('[data-slot="group-chat-message-content"] a', { timeout: 90000 })
      await page.waitForSelector('[data-slot="code-card"] code', { timeout: 30000 })
    } catch (error) {
      fs.writeFileSync(path.join(out, 'capture-failure.json'), JSON.stringify({ errors, consoleErrors, blocked, body: await page.locator('body').innerText(), requests: requests.slice(-15) }, null, 2))
      throw error
    }
    await page.evaluate(() => document.fonts.ready)
    await page.waitForFunction(mode => document.documentElement.dataset.hermesTheme === 'mono' && document.documentElement.dataset.hermesMode === mode, mode)
    const body = page.locator('[data-slot="group-chat-message-content"]')
    assert.equal(await body.count(), 4)
    // Mark fixtures by their real room arrival order; no renderer mutation.
    await body.evaluateAll((nodes, ids) => nodes.forEach((node, i) => node.dataset.fixtureId = ids[i]), ['labeled-apk', 'bare-apk', 'rich-markdown', 'unsafe-inert'])
    const state = await page.evaluate(() => {
      const metrics = element => {
        const css = getComputedStyle(element), rect = element.getBoundingClientRect()
        return { text: element.textContent, color: css.color, background: css.backgroundColor, fontSize: css.fontSize, fontFamily: css.fontFamily,
          lineHeight: css.lineHeight, x: rect.x, y: rect.y, width: rect.width, height: rect.height, scrollWidth: element.scrollWidth }
      }
      const rows = [...document.querySelectorAll('[data-slot="group-chat-message-content"]')].map(row => ({
        id: row.dataset.fixtureId, ...metrics(row),
        anchors: [...row.querySelectorAll('a')].map(a => ({ text: a.textContent, href: a.getAttribute('href'), title: a.getAttribute('title'), ...metrics(a) })),
        inlineCode: [...row.querySelectorAll('p code')].map(code => ({ ...metrics(code), anchors: code.querySelectorAll('a').length })),
        fencedCode: [...row.querySelectorAll('[data-slot="code-card"]')].map(code => ({ ...metrics(code), anchors: code.querySelectorAll('a').length })),
        headings: [...row.querySelectorAll('h1,h2,h3')].map(h => h.textContent), listItems: row.querySelectorAll('li').length,
        unsafeActive: row.querySelectorAll('a[href^="javascript:"],a[href^="data:"]').length,
        html: row.innerHTML
      }))
      return { sha: window.__fixture.sha, kind: window.__fixture.kind, exactSource: window.__fixture.exactSource, workspaceId: window.__fixture.workspaceId,
        fixtures: window.__fixture.fixtures, seams: window.__fixture.seams, theme: { name: document.documentElement.dataset.hermesTheme, mode: document.documentElement.dataset.hermesMode,
          colorScheme: getComputedStyle(document.documentElement).colorScheme, background: getComputedStyle(document.body).backgroundColor },
        viewport: { width: innerWidth, height: innerHeight }, rows }
    })
    assert.equal(state.sha, sha)
    assert.equal(state.exactSource, `[download the latest apk](${url})`)
    assert.equal(state.workspaceId, 'hermes-bots:group:apk-link-fixture')
    assert.equal(state.rows[0].anchors.length, 1)
    assert.equal(state.rows[0].anchors[0].text, 'download the latest apk')
    assert.equal(state.rows[0].anchors[0].href, url)
    assert.equal(state.rows[1].anchors.length, 1)
    assert.equal(state.rows[1].anchors[0].text, url)
    assert.equal(state.rows[1].anchors[0].href, url)
    assert.equal(state.rows[2].anchors.length, 2)
    assert.deepEqual(state.rows[2].headings, ['APK link checks'])
    assert.equal(state.rows[2].listItems, 2)
    assert.equal(state.rows[2].inlineCode.length, 1)
    assert.equal(state.rows[2].inlineCode[0].text, url)
    assert.equal(state.rows[2].inlineCode[0].anchors, 0)
    assert.equal(state.rows[2].fencedCode.length, 1)
    assert.ok(state.rows[2].fencedCode[0].text.includes(`val apkUrl = "${url}"`))
    assert.equal(state.rows[2].fencedCode[0].anchors, 0)
    assert.equal(state.rows[3].unsafeActive, 0)
    states.push(state)
    // Reset actual room log scroller to the top for the reported historical edge.
    await page.evaluate(() => {
      const row = document.querySelector('[data-slot="group-chat-message-content"]')
      for (let node = row.parentElement; node; node = node.parentElement) {
        if (['auto', 'scroll'].includes(getComputedStyle(node).overflowY)) node.scrollTop = 0
      }
    })
    for (const [name, locator] of [[`desktop-group-mono-${mode}-412.png`, null], [`desktop-labeled-apk-mono-${mode}-412.png`, body.nth(0)]]) {
      const file = path.join(out, name)
      if (locator) await locator.screenshot({ path: file, animations: 'disabled' })
      else await page.screenshot({ path: file, animations: 'disabled' })
      screenshots.push({ path: name, sha256: hash(fs.readFileSync(file)) })
    }
    // First: trusted physical touch with a one-shot safety barrier. Observe href,
    // prevent navigation, do not replace anchors or invoke a fake onClick.
    const beforeRoute = page.url()
    await page.evaluate(() => {
      window.__tapEvidence = []
      const anchor = document.querySelector('[data-fixture-id="labeled-apk"] a')
      anchor.addEventListener('click', event => {
        event.preventDefault()
        window.__tapEvidence.push({ text: anchor.textContent, href: anchor.getAttribute('href'), isTrusted: event.isTrusted,
          pointerType: event.pointerType, defaultPrevented: event.defaultPrevented, browserRoute: location.href, barrier: 'one-shot target listener preventDefault' })
      }, { once: true })
    })
    await body.nth(0).locator('a').tap()
    const tap = await page.evaluate(() => window.__tapEvidence)
    assert.equal(tap.length, 1)
    assert.equal(tap[0].href, url)
    assert.equal(tap[0].text, 'download the latest apk')
    assert.equal(tap[0].isTrusted, true)
    assert.equal(tap[0].pointerType, 'touch')
    assert.equal(tap[0].defaultPrevented, true)
    assert.equal(page.url(), beforeRoute)
    // Second: production handler unmodified. It prevents native browser navigation
    // and routes to Desktop's actual preview store; no preview webview is mounted.
    await body.nth(0).locator('a').tap()
    await page.waitForFunction(url => window.__fixture.previewTargets().some(tab => tab.target.url === url), url)
    const productionRouting = await page.evaluate(() => window.__fixture.previewTargets())
    assert.equal(productionRouting.find(tab => tab.target.url === url)?.target.kind, 'url')
    assert.equal(page.url(), beforeRoute)
    taps.push({ mode, safetyBarrierTap: tap[0], productionHandlerTap: { previewTargets: productionRouting, browserRouteBefore: beforeRoute, browserRouteAfter: page.url(), externalNavigation: false } })
    await context.close()
  }
  fs.writeFileSync(path.join(out, 'network-requests.json'), JSON.stringify({ allowedOrigin: origin, requests, blocked, errors, consoleErrors }, null, 2))
  assert.deepEqual(errors, [])
  const expectedBlockedFont = 'https://fonts.googleapis.com/css2?family=Courier+Prime:wght@400;700&display=swap'
  assert.ok(blocked.every(href => href === expectedBlockedFont), JSON.stringify(blocked))
  assert.ok(consoleErrors.every(error => error.message === 'Failed to load resource: net::ERR_BLOCKED_BY_CLIENT.Inspector'), JSON.stringify(consoleErrors))
  assert.equal(consoleErrors.length, blocked.length)
  assert.equal(git('rev-parse', 'HEAD'), sha)
  assert.equal(git('diff', sha, '--', 'apps/desktop/src', 'apps/desktop/vite.config.ts', 'packages'), '')
  const sourceSet = new Set(['apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx', 'apps/desktop/src/components/assistant-ui/markdown-text.tsx', 'apps/desktop/src/lib/external-link.tsx', 'apps/desktop/src/styles.css', 'apps/desktop/src/lib/markdown-preprocess.ts', 'apps/desktop/src/themes/context.tsx', 'apps/desktop/src/themes/presets.ts', 'apps/desktop/src/sdk/index.ts', 'apps/desktop/src/store/preview.ts', 'apps/desktop/vite.config.ts'])
  const tracked = new Set(git('ls-files').split('\n'))
  for (const request of requests) {
    const pathname = decodeURIComponent(new URL(request).pathname)
    const file = pathname.startsWith('/@fs/') ? pathname.slice(4) : path.join(root, pathname)
    const rel = path.relative(repo, file)
    if (tracked.has(rel) && fs.statSync(file).isFile()) sourceSet.add(rel)
  }
  const sources = [...sourceSet].sort().map(file => {
    const bytes = fs.readFileSync(path.join(repo, file))
    const pinned = execFileSync('git', ['-C', repo, 'show', `${sha}:${file}`])
    assert.equal(hash(bytes), hash(pinned), file)
    return { path: file, sha256: hash(bytes), gitBlob: git('rev-parse', `${sha}:${file}`), matchesPinnedBlob: true }
  })
  const fixtureFiles = ['index.html', 'main.tsx', 'capture.mjs', 'README.md']
  const harness = fixtureFiles.map(file => { const bytes = fs.readFileSync(path.join(fixtureDir, file)); fs.copyFileSync(path.join(fixtureDir, file), path.join(out, file)); return { path: `apps/desktop/ignored/group-message-links/${file}`, sha256: hash(bytes) } })
  const captureOnlyDiff = fixtureFiles.map(file => {
    const lines = fs.readFileSync(path.join(fixtureDir, file), 'utf8').trimEnd().split('\n'), dest = `apps/desktop/ignored/group-message-links/${file}`
    return `diff --git a/${dest} b/${dest}\nnew file mode 100644\n--- /dev/null\n+++ b/${dest}\n@@ -0,0 +1,${lines.length} @@\n${lines.map(line => '+' + line).join('\n')}\n`
  }).join('')
  fs.writeFileSync(path.join(out, 'capture-only.patch'), captureOnlyDiff)
  const require = createRequire(path.join(root, 'package.json'))
  const packageVersion = name => {
    try { return require(`${name}/package.json`).version } catch (error) {
      if (error.code !== 'ERR_PACKAGE_PATH_NOT_EXPORTED') throw error
      const entry = import.meta.resolve(name)
      let dir = path.dirname(new URL(entry).pathname)
      for (;;) {
        const file = path.join(dir, 'package.json')
        if (fs.existsSync(file)) { const data = JSON.parse(fs.readFileSync(file, 'utf8')); if (data.name === name) return data.version }
        const parent = path.dirname(dir)
        if (parent === dir) throw new Error(`Package version not found: ${name}`)
        dir = parent
      }
    }
  }
  const versions = Object.fromEntries(['vite', 'react', 'react-dom', '@playwright/test', '@assistant-ui/react', '@assistant-ui/react-streamdown', 'streamdown'].map(name => [name, packageVersion(name)]))
  const manifest = { pinnedSha: sha, actualHead: git('rev-parse', 'HEAD'), fetchHeadNotUsed: git('rev-parse', 'FETCH_HEAD'), startedAtUtc, capturedAtUtc: new Date().toISOString(),
    surface: 'production GroupChatMainView through real openGroupChat render callback (not full Electron application)', fixtureOnly: true,
    fixtureHostSeams: states[0].seams, exactSource: `[download the latest apk](${url})`, versions,
    browser: { channel: 'chrome', version: browser.version(), headless: true, viewport: { width: 412, height: 1100 }, deviceScaleFactor: 1, hasTouch: true, locale: 'en-US', timezone: 'UTC', reducedMotion: 'reduce', fixedFixtureTime: '2026-10-02T12:00:00Z' },
    themes: ['mono-light', 'mono-dark'], androidDefault: { themeName: 'nous', mode: 'System', explicitlyOverriddenForPairedMonoEvidence: true,
      sources: ['app/src/main/kotlin/com/hermesagent/mobile/ui/theme/HermesTheme.kt:26-29', 'app/src/main/kotlin/com/hermesagent/mobile/ui/theme/BuiltinThemes.kt:502'] },
    command: `cd ${shellQuote(root)} && DESKTOP_LINKS_OUT=${shellQuote(out)} node ignored/group-message-links/capture.mjs`, output: out,
    configuration: { productionViteConfig: true, configLoader: 'runner', envDir: false, cacheDir: server.config.cacheDir, host: server.config.server.host, port: server.config.server.port, strictPort: true, hmr: false,
      dependencyReuse: ['node_modules', 'apps/desktop/node_modules'].map(file => ({ path: file, resolved: fs.realpathSync(path.join(repo, file)), access: 'read only; all Vite cache inside capture directory' })),
      noBackend: true, noAuth: true, noElectron: true, noCopiedConfig: true, externalRequestsAborted: true, serviceWorkers: 'block' },
    checks: { exactLabeledSourceRendered: true, exactAnchorLabelAndHref: true, equivalentBareURL: true, headingAndList: true, inlineAndStructuredFenceRemainLiteral: true, unsafeSchemesNotActive: true,
      trustedTouchRecordedWithSafetyBarrier: true, productionUnmodifiedTapRoutesExactHrefToPreviewStore: true, browserDocumentRouteUnchanged: true, externalFetches: 0,
      noRuntimeErrors: true, expectedBlockedFontConsoleErrors: consoleErrors.length, externalRequestsCompleted: 0, sourceBytesEqualPinnedGitBlobs: true, noTrackedProductionChanges: true },
    limitations: ['Initial two failed attempts used Vite default bundle config loader, which writes and unlinks transient .vite-temp files through reused node_modules into installed upstream. Unintended outside-scope transient writes; no production source edits. Final run uses runner loader.', 'Synthetic public fixtures, not a live authenticated room or private transcript.', 'Actual production group view and message renderer; Electron window/tab shell and pane visibility are capture seams.',
      'Production preview store routing verified; preview webview, OS browser, remote artifact authentication, artifact availability and downloads deliberately not exercised.',
      'Production theme inheritance requests Courier Prime CSS; the route guard aborts it before external fetch. Expected blocked-font console messages are recorded, not hidden.',
      'An authored APK label and GitHub artifact URL are a link, not evidence of a downloadable file or attachment.', 'Mono is an explicit comparison setting; Android default is Nous/System. Platform fonts and web-versus-native layout can differ.'],
    sources, harness, screenshots, states, taps, errors, consoleErrors, blocked, requestCount: requests.length, productionTrackedDiff: git('diff', sha, '--', 'apps/desktop/src', 'apps/desktop/vite.config.ts', 'packages'), status: git('status', '--porcelain=v1') }
  fs.writeFileSync(path.join(out, 'capture-manifest.json'), JSON.stringify(manifest, null, 2))
  fs.writeFileSync(path.join(out, 'network-requests.json'), JSON.stringify({ allowedOrigin: origin, requests, blocked }, null, 2))
  console.log(JSON.stringify({ out, pinnedSha: manifest.actualHead, fetchHeadNotUsed: manifest.fetchHeadNotUsed, surface: manifest.surface, versions, browser: manifest.browser.version,
    checks: manifest.checks, sourcesVerified: sources.length, screenshots: screenshots.map(s => s.path), requestCount: requests.length, errors, consoleErrors, blocked }, null, 2))
} finally {
  clearTimeout(deadline)
  if (browser) await browser.close()
  await server.close()
}
