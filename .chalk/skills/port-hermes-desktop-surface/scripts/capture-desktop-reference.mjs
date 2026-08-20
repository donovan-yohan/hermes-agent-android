#!/usr/bin/env node

import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { resolve } from 'node:path'

function usage(message) {
  if (message) console.error(message)
  console.error(`usage: capture-desktop-reference.mjs \\
  --name <surface-state> --selector <root-selector> [options]

options:
  --out <dir>          default: build/visual-parity/<name>/desktop
  --port <port>        default: 9222
  --match <text>       required; select the one page whose URL contains this text
  --upstream <dir>     default: $HERMES_AGENT_UPSTREAM or ~/.hermes/hermes-agent
  --expect-sha <sha>   required; fail unless the upstream checkout is at this exact SHA
  --help
`)
  process.exit(message ? 2 : 0)
}

function parseArgs(argv) {
  const args = {}
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i]
    if (key === '--help') usage()
    if (!key.startsWith('--')) usage(`unexpected argument: ${key}`)
    const value = argv[++i]
    if (!value || value.startsWith('--')) usage(`missing value for ${key}`)
    args[key.slice(2)] = value
  }
  if (!args.name) usage('--name is required')
  if (!args.selector) usage('--selector is required')
  if (!args.match) usage('--match is required')
  if (!args['expect-sha']) usage('--expect-sha is required')
  return args
}

function git(upstream, ...args) {
  return execFileSync('git', ['-C', upstream, ...args], { encoding: 'utf8' }).trim()
}

async function discoverTarget(port, match) {
  const response = await fetch(`http://127.0.0.1:${port}/json/list`)
  if (!response.ok) throw new Error(`CDP target list returned HTTP ${response.status}`)
  const targets = await response.json()
  const pages = targets.filter(target => target.type === 'page' && target.webSocketDebuggerUrl)
  const matches = pages.filter(page => String(page.url).includes(match))
  if (matches.length !== 1) {
    const urls = matches.map(page => page.url).join('\n') || '(none)'
    throw new Error(`expected exactly one CDP page on :${port} matching ${JSON.stringify(match)}, found ${matches.length}:\n${urls}`)
  }
  return matches[0]
}

class CDP {
  constructor(socket) {
    this.socket = socket
    this.sequence = 0
    this.pending = new Map()
    socket.addEventListener('message', event => {
      const message = JSON.parse(typeof event.data === 'string' ? event.data : event.data.toString('utf8'))
      if (message.id == null || !this.pending.has(message.id)) return
      const request = this.pending.get(message.id)
      this.pending.delete(message.id)
      if (message.error) request.reject(new Error(message.error.message))
      else request.resolve(message.result)
    })
    socket.addEventListener('close', () => {
      for (const request of this.pending.values()) request.reject(new Error('CDP socket closed'))
      this.pending.clear()
    })
  }

  static async open(url) {
    const socket = new WebSocket(url)
    await new Promise((resolveOpen, rejectOpen) => {
      socket.addEventListener('open', resolveOpen, { once: true })
      socket.addEventListener('error', rejectOpen, { once: true })
    })
    return new CDP(socket)
  }

  send(method, params = {}) {
    const id = ++this.sequence
    return new Promise((resolveRequest, rejectRequest) => {
      this.pending.set(id, { resolve: resolveRequest, reject: rejectRequest })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }

  async eval(expression) {
    const response = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    })
    if (response.exceptionDetails) {
      throw new Error(response.exceptionDetails.exception?.description ?? response.exceptionDetails.text ?? 'evaluation failed')
    }
    return response.result.value
  }

  close() {
    this.socket.close()
  }
}

const args = parseArgs(process.argv.slice(2))
const upstream = resolve(args.upstream ?? process.env.HERMES_AGENT_UPSTREAM ?? `${homedir()}/.hermes/hermes-agent`)
const sha = git(upstream, 'rev-parse', 'HEAD')
const dirty = git(upstream, 'status', '--porcelain')
if (dirty) throw new Error(`upstream checkout is dirty; reference capture refused:\n${dirty}`)
if (sha !== args['expect-sha']) {
  throw new Error(`upstream SHA mismatch: expected ${args['expect-sha']}, found ${sha}`)
}

const port = Number(args.port ?? '9222')
if (!Number.isInteger(port) || port < 1 || port > 65535) usage(`invalid --port: ${args.port}`)
const target = await discoverTarget(port, args.match)
const cdp = await CDP.open(target.webSocketDebuggerUrl)
const selector = args.selector

try {
  await cdp.send('Page.enable')
  const packet = await cdp.eval(`(() => {
    const selector = ${JSON.stringify(selector)}
    const matches = [...document.querySelectorAll(selector)]
    if (matches.length !== 1) {
      throw new Error('expected exactly one root for ' + selector + ', found ' + matches.length)
    }
    const root = matches[0]
    const properties = [
      'display', 'position', 'width', 'height', 'minWidth', 'minHeight',
      'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
      'marginTop', 'marginRight', 'marginBottom', 'marginLeft', 'gap',
      'fontFamily', 'fontSize', 'fontWeight', 'lineHeight', 'letterSpacing',
      'textTransform', 'textAlign', 'color', 'backgroundColor',
      'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
      'borderRadius', 'opacity'
    ]
    const describe = (element, index) => {
      const style = getComputedStyle(element)
      const before = getComputedStyle(element, '::before')
      const rect = element.getBoundingClientRect()
      const computed = Object.fromEntries(properties.map(property => [property, style[property]]))
      return {
        index,
        tag: element.tagName.toLowerCase(),
        id: element.id || null,
        role: element.getAttribute('role'),
        slot: element.getAttribute('data-slot'),
        ariaLabel: element.getAttribute('aria-label'),
        text: (element.innerText || element.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 240),
        classes: typeof element.className === 'string' ? element.className : null,
        rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
        computed,
        before: before.content && before.content !== 'none'
          ? { content: before.content, fontFamily: before.fontFamily, fontSize: before.fontSize }
          : null
      }
    }
    const descendants = [...root.querySelectorAll('*')]
    const rootRect = root.getBoundingClientRect()
    return {
      capturedAt: new Date().toISOString(),
      title: document.title,
      url: location.href,
      viewport: {
        width: innerWidth,
        height: innerHeight,
        devicePixelRatio,
        scrollX,
        scrollY
      },
      clip: {
        x: rootRect.x + scrollX,
        y: rootRect.y + scrollY,
        width: rootRect.width,
        height: rootRect.height,
        scale: 1
      },
      nodeCount: descendants.length + 1,
      nodes: [describe(root, 0), ...descendants.map((element, index) => describe(element, index + 1))]
    }
  })()`)

  if (!packet.clip.width || !packet.clip.height) throw new Error(`root ${JSON.stringify(selector)} has no visible area`)
  const image = await cdp.send('Page.captureScreenshot', {
    format: 'png',
    fromSurface: true,
    captureBeyondViewport: true,
    clip: packet.clip,
  })

  const out = resolve(args.out ?? `build/visual-parity/${args.name}/desktop`)
  mkdirSync(out, { recursive: true })
  writeFileSync(`${out}/reference.png`, Buffer.from(image.data, 'base64'))
  writeFileSync(`${out}/contract.json`, `${JSON.stringify({
    capture: packet,
    reference: {
      name: args.name,
      rootSelector: selector,
      upstream,
      upstreamSha: sha,
      target: { title: target.title, url: target.url },
    },
  }, null, 2)}\n`)
  console.log(`desktop reference: ${out}/reference.png`)
  console.log(`computed contract: ${out}/contract.json`)
  console.log(`upstream SHA: ${sha}`)
} finally {
  cdp.close()
}
