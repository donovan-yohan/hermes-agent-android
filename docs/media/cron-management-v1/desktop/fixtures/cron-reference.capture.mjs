import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { chromium } from 'playwright'

const root = path.resolve(import.meta.dirname, '../..')
const out = process.env.CRON_EVIDENCE_DIR
assert.ok(out, 'CRON_EVIDENCE_DIR must name a separate writable capture directory')
assert.ok(fs.existsSync(path.join(import.meta.dirname, 'cron-reference.html')), 'additive synthetic cron reference fixture must exist')
if (process.argv.includes('--preflight')) { console.log('PASS: additive fixture exists'); process.exit(0) }
fs.mkdirSync(path.join(out,'screenshots'),{recursive:true})
fs.mkdirSync(path.join(out,'receipts'),{recursive:true})
const sha = data => crypto.createHash('sha256').update(data).digest('hex')
const sources = [
 'apps/desktop/src/app/cron/index.tsx','apps/desktop/src/app/cron/blueprints.tsx',
 'apps/desktop/src/app/cron/cron-job-model.ts','apps/desktop/src/app/cron/open-cron-run.ts',
 'apps/desktop/src/app/cron/job-state.ts','apps/desktop/src/app/cron/cron-actions.ts',
 'apps/desktop/src/app/chat/sidebar/cron-jobs-section.tsx','apps/desktop/src/app/overlays/panel.tsx',
 'apps/desktop/src/app/overlays/overlay-view.tsx','apps/desktop/src/styles.css',
 'apps/desktop/src/themes/context.tsx','apps/desktop/src/themes/presets.ts','apps/desktop/src/i18n/en.ts',
 'apps/desktop/src/api/cron.ts','apps/desktop/cron-reference.html','apps/desktop/cron-reference.tsx',
 'apps/desktop/cron-reference.capture.mjs'
]
const sourceHashes = Object.fromEntries(sources.map(f=>[f,sha(fs.readFileSync(path.join(root,f)))]))
const fontSources = JSON.parse(fs.readFileSync(path.join(out,'fonts/sources.json')))
for (const font of fontSources) assert.equal(sha(fs.readFileSync(path.join(out,'fonts',font.file))),font.sha256,'cached public font source hash')
const before = JSON.parse(fs.readFileSync(path.join(out,'tracked-before.json')))
const events=[]
const captures=[]
const errors=[]
const blockedRequests=[]
let checks=0
let page
let theme
const record=(kind,name,detail={})=>events.push({theme,kind,name,...detail})
function check(value,name) {assert.ok(value,name); checks++; record('assert',name)}
async function visible(locator,name) {await locator.waitFor({state:'visible'});checks++;record('assert',name)}
async function click(locator,name) {await locator.click();record('click',name)}
async function fill(locator,value,name) {await locator.fill(value);record('fill',name,{value})}
async function snapshot() {return page.evaluate(()=>window.__cronFixture.snapshot())}
async function capture(name,selector,copy=[]) {
 await page.mouse.move(1250,30)
 await page.evaluate(async()=>{await document.fonts.load('12px "Courier Prime"');await document.fonts.ready})
 check(await page.evaluate(()=>document.fonts.check('12px "Courier Prime"')),'mono Courier Prime font loaded from verified public cache')
 for (const text of copy) check((await page.locator('body').innerText()).includes(text),`${name}: copy ${text}`)
 const filename=`${theme}-${name}.png`
 const screenshot=path.join(out,'screenshots',filename)
 await page.screenshot({path:screenshot,animations:'disabled'})
 const dom=await page.evaluate(sel=>({
  visibleCopy:document.body.innerText,
  controls:[...document.querySelectorAll('button,input,textarea,[role="combobox"],[role="checkbox"],[role="menuitem"]')].filter(e=>e.getBoundingClientRect().width && e.getBoundingClientRect().height).map(e=>({tag:e.tagName,role:e.getAttribute('role'),label:e.getAttribute('aria-label') ?? e.textContent,id:e.id,value:e.value,disabled:e.disabled,expanded:e.getAttribute('aria-expanded')})),
  target:[...document.querySelectorAll(sel)].map(e=>({selector:sel,text:e.textContent,rect:e.getBoundingClientRect().toJSON()})),
  appearance:{className:document.documentElement.className,theme:document.documentElement.dataset,background:getComputedStyle(document.body).backgroundColor,foreground:getComputedStyle(document.body).color,font:getComputedStyle(document.body).fontFamily},
  fixture:{state:window.__cronFixture.state,theme:window.__cronFixture.theme,clock:window.__cronFixture.fixedClock},
  backend:window.__cronFixture.snapshot()
 }),selector)
 const receipt={file:`screenshots/${filename}`,sha256:sha(fs.readFileSync(screenshot)),pin:'5fc490e2b34123212d73ef316086f939567a725a',synthetic:true,liveBackend:false,scope:'Unchanged real CronView + SidebarCronJobsSection, fixture framing; NOT full Desktop shell',theme,skin:'mono',locale:'en-US',timezone:'UTC',viewport:{width:1280,height:960,deviceScaleFactor:1},browserVersion:browser.version(),sourceHashes,fontSources,trackedBeforeManifestSha256:sha(fs.readFileSync(path.join(out,'tracked-before.json'))),selector,requiredCopy:copy,...dom}
 fs.writeFileSync(path.join(out,'receipts',`${theme}-${name}.json`),JSON.stringify(receipt,null,2))
 captures.push({name,theme,file:receipt.file,sha256:receipt.sha256,receipt:`receipts/${theme}-${name}.json`})
 console.log(`CAPTURE ${theme}/${name}`)
}
async function load(state='populated') {
 await page.goto(`${process.env.CRON_REFERENCE_URL}/cron-reference.html?theme=${theme}&state=${state}`)
 await visible(page.getByText('SYNTHETIC FIXTURE — no live backend',{exact:true}),`${state}: fixture disclosure`)
 if (state==='empty' || state==='load-error') await visible(page.getByText('No scheduled jobs yet',{exact:true}),`${state}: settled empty`)
 else await visible(page.locator('[data-panel-row="report-daily"]'),`${state}: jobs loaded`)
  check(await page.evaluate(()=>Date.now())===Date.parse('2026-10-19T09:00:00Z'),'fixed clock')
 check(await page.evaluate(()=>document.documentElement.classList.contains('dark'))===(theme==='dark'),'real ThemeProvider mode')
}
const reportRow=()=>page.locator('[data-panel-row="report-daily"]')
const scriptRow=()=>page.locator('[data-panel-row="script-check"]')
async function selectReport() {
 await click(reportRow().getByRole('button',{name:'Status report',exact:true}),'select Status report')
 await visible(page.getByRole('heading',{name:'Status report',exact:true}),'Status report detail')
 await visible(page.getByRole('button',{name:/Status report — active/}),'active run loaded')
}
async function editRow(row) {
 await row.hover()
 await click(row.getByRole('button',{name:'Manage',exact:true}),'open job menu')
 await click(page.getByRole('menuitem',{name:'Edit cron',exact:true}),'Edit cron')
 await visible(page.getByRole('dialog',{name:'Edit cron job'}),'edit dialog')
}
async function cancelDialog() {await click(page.getByRole('button',{name:'Cancel',exact:true}),'cancel dialog');await page.getByRole('dialog').waitFor({state:'hidden'})}
const browser=await chromium.launch({headless:true,executablePath:process.env.CRON_CHROMIUM})
try {
 for (theme of ['dark','light']) {
  const context=await browser.newContext({viewport:{width:1280,height:960},locale:'en-US',timezoneId:'UTC',reducedMotion:'reduce',deviceScaleFactor:1})
  page=await context.newPage()
  page.setDefaultTimeout(20000)
  page.on('pageerror',e=>errors.push({theme,type:'pageerror',message:e.message}))
  page.on('console',m=>{if(m.type()==='error')errors.push({theme,type:'console',message:m.text()})})
  await page.route('**/*',async route=>{
   const url=new URL(route.request().url())
   if(url.hostname==='127.0.0.1') return route.continue()
   const font=fontSources.find(f=>f.url===url.href)
   if(font) return route.fulfill({status:200,contentType:font.contentType,body:fs.readFileSync(path.join(out,'fonts',font.file)),headers:{'Access-Control-Allow-Origin':'*'}})
   blockedRequests.push({theme,url:url.href});return route.abort('blockedbyclient')
  })
  await load()
  await selectReport()
  await capture('populated-detail','[data-overlay-surface]',['Scheduled jobs','Every 15 minutes','Prepare a status report','RUN HISTORY · 2','This desktop'])
  await click(page.getByRole('button',{name:'Status report',exact:false}).filter({hasText:'10/18/2026'}),'open completed run')
  let snap=await snapshot()
  check(snap.opened.at(-1).id==='local-cron-completed' && snap.opened.at(-1).row.profile==='default','completed run preserves owner row')
  await click(page.getByRole('button',{name:/Status report — active/}),'open active run')
  snap=await snapshot()
  check(snap.opened.at(-1).id==='local-cron-active' && snap.opened.at(-1).row.scheduler_owned===true,'scheduler-owned active run preserves liveness')

  await reportRow().hover()
  await click(reportRow().getByRole('button',{name:'Manage',exact:true}),'open manager menu')
  await visible(page.getByRole('menuitem',{name:'Edit cron'}),'menu edit')
  await capture('manager-menu','[role="menu"]',['Edit cron','Delete'])
  await click(page.getByRole('menuitem',{name:'Edit cron'}),'Edit cron')
  await visible(page.getByRole('dialog',{name:'Edit cron job'}),'editor open')
  check(await page.locator('#cron-name').inputValue()==='Status report','edit name is exact')
  check(await page.locator('#cron-prompt').inputValue()==='Prepare a status report','edit prompt is exact')
  await capture('editor-edit','[role="dialog"]',['Edit cron job','Every 15 minutes','Save changes'])
  await click(page.locator('#cron-frequency'),'frequency options')
  check(await page.getByRole('option').count()===7,'all seven schedule choices')
  await capture('frequency-options','[role="listbox"]',['Daily','Weekdays','Weekly','Monthly','Hourly','Every 15 minutes','Custom'])
  await click(page.getByRole('option',{name:'Custom',exact:true}),'custom schedule')
  await fill(page.locator('#cron-schedule'),'*/15 * * * *','custom exact cron expression')
  await capture('editor-custom','[role="dialog"]',['Custom schedule','Cron expression, or phrases'])
  await click(page.locator('#cron-model'),'model catalog')
  await visible(page.getByRole('option',{name:'synthetic-model',exact:true}),'synthetic catalog model')
  await capture('model-options','[role="listbox"]',['Default (global model)','Synthetic fixture provider','synthetic-model'])
  await click(page.getByRole('option',{name:'synthetic-model',exact:true}),'select synthetic provider/model pair')
  await click(page.getByRole('button',{name:'Save changes',exact:true}),'save existing job model choice')
  await page.getByRole('dialog').waitFor({state:'hidden'})
  snap=await snapshot()
  check(snap.jobs.find(j=>j.id==='report-daily').provider==='synthetic' && snap.jobs.find(j=>j.id==='report-daily').model==='synthetic-model','provider/model submitted together')
  await editRow(reportRow())
  await click(page.locator('#cron-model'),'model reset options')
  await click(page.getByRole('option',{name:'Default (global model)',exact:true}),'reset global model')
  await click(page.getByRole('button',{name:'Save changes',exact:true}),'save reset')
  await page.getByRole('dialog').waitFor({state:'hidden'})
  snap=await snapshot()
  check(!snap.jobs.find(j=>j.id==='report-daily').model && !snap.jobs.find(j=>j.id==='report-daily').provider,'model/provider reset together')
  await click(page.getByRole('button',{name:'Pause',exact:true}),'pause real manager action')
  await visible(page.getByRole('button',{name:'Resume',exact:true}),'pause refreshed to Resume')
  check((await snapshot()).jobs.find(j=>j.id==='report-daily').state==='paused','pause readback')
  await capture('paused-detail','[data-overlay-surface]',['paused','Resume'])
  await click(page.getByRole('button',{name:'Resume',exact:true}),'resume real manager action')
  await visible(page.getByRole('button',{name:'Pause',exact:true}),'resume refreshed to Pause')
  check((await snapshot()).jobs.find(j=>j.id==='report-daily').enabled===true,'resume readback')

  await click(page.getByRole('button',{name:'New cron',exact:true}),'new cron')
  await visible(page.getByRole('dialog',{name:'New cron job'}),'create dialog')
  await capture('editor-create','[role="dialog"]',['New cron job','Start from','Daily','Default (global model)','Create cron'])
  await click(page.getByRole('button',{name:'Create cron',exact:true}),'validate blank prompt')
  await visible(page.getByText('Prompt is required.',{exact:true}),'prompt required validation')
  await capture('editor-validation','[role="dialog"]',['Prompt is required.'])
  await fill(page.locator('#cron-name'),'Synthetic created check','create name')
  await fill(page.locator('#cron-prompt'),'Synthetic prompt only','create prompt')
  await click(page.getByRole('checkbox',{name:'Telegram',exact:true}),'add Telegram delivery')
  await capture('editor-multidelivery','[role="dialog"]',['This desktop','Telegram','Slack — set a home channel first'])
  await click(page.getByRole('button',{name:'Create cron',exact:true}),'create synthetic job')
  await page.getByRole('dialog').waitFor({state:'hidden'})
  await visible(page.locator('[data-panel-row="synthetic-created"]'),'create rendered after refresh')
  snap=await snapshot()
  check(snap.jobs.find(j=>j.id==='synthetic-created').deliver==='local,telegram','multiple delivery payload retained')
  const created=page.locator('[data-panel-row="synthetic-created"]')
  await created.hover()
  await click(created.getByRole('button',{name:'Manage',exact:true}),'created menu')
  await click(page.getByRole('menuitem',{name:'Delete',exact:true}),'request delete')
  await visible(page.getByRole('dialog',{name:'Delete cron job?'}),'delete confirmation')
  await capture('delete-confirm','[role="dialog"]',['Delete cron job?','This will remove','Synthetic created check','permanently. It will stop firing immediately.'])
  await cancelDialog()
  check((await snapshot()).jobs.some(j=>j.id==='synthetic-created'),'cancel delete preserves job')
  await created.hover()
  await click(created.getByRole('button',{name:'Manage',exact:true}),'created menu for confirmed delete')
  await click(page.getByRole('menuitem',{name:'Delete',exact:true}),'request confirmed delete')
  await click(page.getByRole('dialog').getByRole('button',{name:'Delete',exact:true}),'confirm delete')
  await page.getByRole('dialog').waitFor({state:'hidden'})
  await created.waitFor({state:'hidden'})
  check(!(await snapshot()).jobs.some(j=>j.id==='synthetic-created'),'delete readback absent')

  await click(scriptRow().getByRole('button',{name:'Script check',exact:true}),'script detail')
  await visible(page.getByRole('heading',{name:'Script check',exact:true}),'script heading')
  await visible(page.getByText('printf scheduled',{exact:true}),'script body')
  await capture('script-detail','[data-overlay-surface]',['Script','SCRIPT','printf scheduled','scheduled'])
  const beforeOpen=(await snapshot()).opened.length
  check(await page.getByRole('button',{name:/scheduled.*10\/18/}).count()===0,'cron_output has no navigation button')
  check((await snapshot()).opened.length===beforeOpen,'output doc remains inert')
  await editRow(scriptRow())
  await capture('script-editor','[role="dialog"]',['Script-only job (no AI prompt). Job id:','script-check','Optional','Save changes'])
  check(await page.locator('#cron-model').count()===0,'script-only editor omits model picker')
  check(await page.locator('#cron-prompt').inputValue()==='','script-only prompt stays blank')
  await click(page.getByRole('button',{name:'Save changes',exact:true}),'save script with blank prompt')
  await page.getByRole('dialog').waitFor({state:'hidden'})
  snap=await snapshot()
  check(snap.jobs.find(j=>j.id==='script-check').no_agent===true && snap.jobs.find(j=>j.id==='script-check').script==='printf scheduled' && snap.jobs.find(j=>j.id==='script-check').prompt==='','script-only identity preserved')

  await click(page.locator('[data-panel-row="blueprint-synthetic-recipe"]').getByRole('button'),'open Synthetic Recipe')
  await visible(page.locator('#blueprint-synthetic-recipe-time'),'blueprint typed time')
  await capture('blueprint-editor','[role="dialog"]',['Synthetic Recipe','Synthetic field-schema example; not a backend recipe.','Time','Cadence','Weekdays','Topic','Deliver to','Schedule it'])
  check(await page.locator('#blueprint-synthetic-recipe-time').inputValue()==='09:00','blueprint time default')
  check(await page.getByRole('checkbox',{name:'This desktop',exact:true}).getAttribute('aria-checked')==='true','blueprint origin seeds to local')
  await fill(page.locator('#blueprint-synthetic-recipe-time'),'10:30','blueprint time change')
  await fill(page.locator('#blueprint-synthetic-recipe-topic'),'Synthetic chosen topic','blueprint text change')
  await click(page.getByRole('button',{name:'Schedule it',exact:true}),'instantiate synthetic blueprint')
  await page.getByRole('dialog').waitFor({state:'hidden'})
  await visible(page.locator('[data-panel-row="synthetic-blueprint-created"]'),'blueprint-created job rendered')
  snap=await snapshot()
  const instantiate=snap.calls.find(c=>c.path.includes('/blueprints/instantiate'))
  check(instantiate.body.values.time==='10:30' && instantiate.body.values.deliver==='local' && instantiate.body.values.topic==='Synthetic chosen topic','blueprint typed values passed unchanged')

  await load()
  await selectReport()
  await click(page.getByRole('button',{name:'Close cron',exact:true}),'close manager to actual sidebar')
  await click(page.locator('[data-fixture-sidebar]').getByRole('button',{name:'Show runs',exact:true}).filter({hasText:'Status report'}),'expand sidebar Status report')
  await visible(page.locator('[data-fixture-sidebar]').getByRole('button',{name:'Oct 18, 5:01 PM',exact:true}),'sidebar closed run timestamp')
  await capture('sidebar-expanded','[data-fixture-sidebar]',['SCHEDULED JOBS','Status report','Oct 18, 5:01 PM'])
  await click(page.locator('[data-fixture-sidebar]').getByRole('button',{name:'Oct 18, 5:01 PM',exact:true}),'open sidebar completed run')
  check((await snapshot()).opened.at(-1).id==='local-cron-completed','sidebar opens completed run exact row')
  const sidebarReport=page.locator('[data-fixture-sidebar]').getByRole('button',{name:'Hide runs',exact:true}).filter({hasText:'Status report'})
  await sidebarReport.click({button:'right'})
  record('contextclick','sidebar Status report')
  await capture('sidebar-actions','[role="menu"]',['Trigger now','Pause cron','Manage','Delete'])
  await click(page.getByRole('menuitem',{name:'Manage',exact:true}),'sidebar Manage focuses manager')
  await visible(page.getByRole('heading',{name:'Status report',exact:true}),'sidebar Manage opens correct job')
  check(await reportRow().getAttribute('class').then(c=>c.includes('bg-')),'focused manager row present')

  await load('empty')
  await capture('empty','[data-overlay-surface]',['Scheduled jobs','No scheduled jobs yet','Schedule a prompt to run on a cron expression.','New cron'])
  await click(page.getByRole('button',{name:'New cron',exact:true}),'empty create action')
  await visible(page.getByRole('dialog',{name:'New cron job'}),'empty launches real editor')
  await cancelDialog()

  await load('job-error')
  await selectReport()
  await capture('job-error','[data-overlay-surface]',['Last run failed:','Synthetic fixture: scheduled task failed','Edit job','Run again'])

  await load('load-error')
  await visible(page.getByText('Failed to load cron jobs',{exact:true}),'list failure notification')
  await capture('load-error-notification','[data-overlay-surface]',['Failed to load cron jobs','Synthetic fixture: cron list unavailable','No scheduled jobs yet'])

  await load()
  await selectReport()
  await click(page.getByRole('button',{name:'Trigger now',exact:true}),'held synthetic trigger')
  await visible(page.locator('[data-slot="cron-run-pending"]'),'optimistic queued run appears')
  check(await page.getByRole('button',{name:'Trigger now',exact:true}).isDisabled(),'trigger duplicate guarded while held')
  check((await snapshot()).calls.filter(c=>c.path.endsWith('/trigger')).length===1,'only one held trigger dispatched')
  await capture('queued-trigger','[data-slot="cron-run-pending"]',['Queued run','RUN HISTORY · 3'])
  await context.close()
 }
 check(errors.filter(e=>e.type==='pageerror' || !e.message.includes('ERR_BLOCKED_BY_CLIENT')).length===0,'zero unplanned browser page/console errors; isolation aborts retained')
 check(captures.length===40,'paired capture count matches target')
 fs.writeFileSync(path.join(out,'capture-index.json'),JSON.stringify(captures,null,2))
 console.log(`PASS ${checks} assertions; ${captures.length} captures; ${errors.length} browser errors`)
} catch(e) {
 fs.writeFileSync(path.join(out,'failure.json'),JSON.stringify({message:e.message,stack:e.stack,theme,checks,captures,errors},null,2))
 if(page&&!page.isClosed()) {fs.writeFileSync(path.join(out,'failure-dom.txt'),await page.locator('body').innerText());await page.screenshot({path:path.join(out,'failure.png')})}
 throw e
} finally {
 fs.writeFileSync(path.join(out,'interaction-events.json'),JSON.stringify(events,null,2))
 fs.writeFileSync(path.join(out,'browser-errors.json'),JSON.stringify(errors,null,2))
 fs.writeFileSync(path.join(out,'blocked-requests.json'),JSON.stringify(blockedRequests,null,2))
 fs.writeFileSync(path.join(out,'execution-summary.json'),JSON.stringify({assertions:checks,captures:captures.length,errors:errors.length,states:[...new Set(captures.map(c=>c.name))],themes:[...new Set(captures.map(c=>c.theme))]},null,2))
 await browser.close()
}
