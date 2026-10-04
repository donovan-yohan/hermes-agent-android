import fs from 'node:fs'
import path from 'node:path'
import { setupMockBackend, waitForAppReady } from './fixtures'
import { allowErrorBanners, expect, test } from './test'

for (const scenario of ['defaults','pinned','loading','error']) {
 test(`current Tools ${scenario}`, async () => {
  test.setTimeout(240_000); allowErrorBanners()
  const fixture=await setupMockBackend(); const {page,app}=fixture
  const out=process.env.PARITY_OUT!; fs.mkdirSync(out,{recursive:true})
  try {
   await waitForAppReady(fixture,120_000)
   await page.context().tracing.start({screenshots:true,snapshots:true,sources:true})
   const cdp=await page.context().newCDPSession(page)
   await cdp.send('Emulation.setTimezoneOverride',{timezoneId:'UTC'})
   await cdp.send('Emulation.setLocaleOverride',{locale:'en-US'})
   await page.emulateMedia({colorScheme:'light',reducedMotion:'reduce'})
   await page.evaluate(()=>{
    localStorage.setItem('hermes-desktop-theme-v2','mono')
    localStorage.setItem('hermes-desktop-mode-v1','system')
    localStorage.setItem('hermes-desktop-profile-themes-v1','{}')
    localStorage.setItem('hermes-desktop-profile-modes-v1','{}')
    window.dispatchEvent(new StorageEvent('storage',{key:'hermes-desktop-theme-v2',newValue:'mono'}))
   })
   await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
   await page.getByRole('button',{name:'New bot or group chat'}).click()
   await page.getByRole('menuitem',{name:/^New bot$/i}).click()
   const create=page.getByRole('dialog',{name:/^New bot$/i})
   await create.getByPlaceholder('inbox-triage').fill('synthetic-toolsets')
   await create.getByPlaceholder('Inbox Triage').fill('Synthetic Toolsets')
   await create.getByRole('button',{name:/^Create bot$/i}).click()
   await expect(create).toBeHidden({timeout:60_000})
   // Same real main-process IPC interception seam as completed-reply-refresh.spec.ts.
   await app.evaluate(({ipcMain},scenario)=>{
    const original=(ipcMain as any)._invokeHandlers.get('hermes:api')
    const probe=(globalThis as any).__toolsetsCapture={scenario,log:[],pendingReads:0,pendingWrites:0,selected:['web'],pinned:scenario==='pinned',release:null}
    const rows=()=>[
     {name:'web',label:'Web',description:'Search and read web pages',tools:['web_search','web_extract']},
     {name:'terminal',label:'Terminal',description:'Run shell commands',tools:['terminal']}
    ].map(row=>({...row,enabled:probe.selected.includes(row.name),available:probe.selected.includes(row.name),configured:true,platform:'cli',platform_label:'CLI'}))
    ipcMain.removeHandler('hermes:api')
    ipcMain.handle('hermes:api',async(event,request)=>{
     if(request.profile!=='synthetic-toolsets'||!request.path.startsWith('/api/tools/toolsets'))return original(event,request)
     const log={request:JSON.parse(JSON.stringify(request)),phase:'admitted',at:Date.now(),result:null as any};probe.log.push(log)
     if(request.path==='/api/tools/toolsets'){
      if(scenario==='loading'){probe.pendingReads++;return new Promise(()=>{})}
      if(scenario==='error'){log.phase='refused';throw Error('Synthetic read failure')}
      log.result=rows();log.phase='read';return log.result
     }
     if(request.method==='PUT'&&request.path==='/api/tools/toolsets/terminal'){
      if(JSON.stringify(request.body)!==JSON.stringify({enabled:true}))throw Error('Unexpected synthetic write')
      probe.pendingWrites++
      await new Promise(resolve=>{probe.release=resolve})
      probe.selected=['web','terminal'];probe.pinned=true;probe.pendingWrites--
      log.phase='committed';log.result={ok:true,name:'terminal',enabled:true};return log.result
     }
     // Real configuration panels remain backed by this isolated sandbox.
     log.phase='passthrough';return original(event,request)
    })
   },scenario)
   await page.addInitScript(({scenario})=>{
    const w=window as any;w.__toolsetsRpc=[]
    const send=WebSocket.prototype.send;const sockets=new WeakSet<WebSocket>()
    WebSocket.prototype.send=function(text){
     let f:any;try{f=JSON.parse(String(text))}catch{return send.call(this,text)}
     if(f.method==='profiles.describe'&&f.params?.name==='synthetic-toolsets')sockets.add(this)
     if(!sockets.has(this)||f.params?.name!=='synthetic-toolsets'||f.method!=='profiles.describe')return send.call(this,text)
     w.__toolsetsRpc.push(f)
     const result={name:'synthetic-toolsets',model:{provider:'fixture',default:'model-alpha'},soul:'Synthetic toolsets reference.',skills:[],mcp_servers:[],toolsets_pinned:scenario==='pinned',toolsets:[{name:'web',label:'Web',description:'Search and read web pages',enabled:true,tool_count:2},{name:'terminal',label:'Terminal',description:'Run shell commands',enabled:false,tool_count:1}]}
     queueMicrotask(()=>this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({jsonrpc:'2.0',id:f.id,result})})))
    }
   },{scenario})
   await page.reload();await waitForAppReady(fixture,120_000)
   await page.clock.setFixedTime(new Date('2026-09-17T16:00:00Z'))
   await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
   const editor=page.getByRole('dialog',{name:'Edit profile'})
   async function open(){
    await page.locator('[data-roster-key="local::synthetic-toolsets"]').click({button:'right'})
    await page.getByRole('menuitem',{name:/^Edit/}).first().click()
    await editor.getByRole('button',{name:/^Advanced/}).click()
    await editor.getByRole('button',{name:/^Tools(?:\s|$)/}).first().click()
    await editor.getByRole('button',{name:/^Advanced/}).evaluate(el=>el.scrollIntoView({block:'start'}))
   }
   async function capture(state:string){
    for(const theme of ['light','dark'] as const){
     await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'})
     await expect(page.locator('html')).toHaveAttribute('data-hermes-mode',theme)
     await expect(page.locator('html')).toHaveAttribute('data-hermes-theme','mono')
     const normalization=await page.evaluate(()=>({skin:document.documentElement.dataset.hermesTheme,mode:document.documentElement.dataset.hermesMode,locale:Intl.DateTimeFormat().resolvedOptions().locale,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,clock:new Date().toISOString()}))
     expect(normalization.locale).toBe('en-US');expect(normalization.timezone).toBe('UTC')
     await editor.screenshot({path:path.join(out,`${state}-${theme}.png`),animations:'disabled'})
     fs.writeFileSync(path.join(out,`${state}-${theme}.json`),JSON.stringify({state,theme,normalization,text:await editor.innerText(),nodes:await editor.ariaSnapshot(),probe:await app.evaluate(()=>{const p=(globalThis as any).__toolsetsCapture;return {...p,release:undefined}}),rpc:await page.evaluate(()=>(window as any).__toolsetsRpc)},null,2))
    }
   }
   await open()
   if(scenario==='loading'){
    await expect.poll(()=>app.evaluate(()=>(globalThis as any).__toolsetsCapture.pendingReads)).toBeGreaterThan(0)
    await expect(editor.getByRole('switch')).toHaveCount(0)
    await capture('loading')
   }else if(scenario==='error'){
    await expect(editor.getByRole('button',{name:'Refresh skills',exact:true})).toBeVisible({timeout:30_000})
    await capture('error')
   }else{
    await expect(editor.getByRole('switch',{name:'Turn Terminal toolset on'})).toBeVisible()
    await expect(editor.getByRole('switch',{name:'Turn Web toolset off'})).toBeChecked()
    await capture(scenario==='defaults'?'loaded-defaults':'loaded-pinned')
    if(scenario==='pinned'){
     await editor.getByRole('switch',{name:'Turn Terminal toolset on'}).click()
     await expect.poll(()=>app.evaluate(()=>(globalThis as any).__toolsetsCapture.pendingWrites)).toBe(1)
     await expect(editor.getByRole('switch',{name:'Turn Terminal toolset off'})).toBeChecked()
     await capture('changed-autosave-pending')
     await app.evaluate(()=>(globalThis as any).__toolsetsCapture.release())
     await expect.poll(()=>app.evaluate(()=>(globalThis as any).__toolsetsCapture.pendingWrites)).toBe(0)
     await capture('saved-autosave')
     await editor.getByRole('button',{name:'Cancel',exact:true}).click()
     await open()
     await expect(editor.getByRole('switch',{name:'Turn Terminal toolset off'})).toBeChecked()
     await capture('saved-reopened')
    }
   }
   await page.context().tracing.stop({path:path.join(out,`${scenario}.trace.zip`)})
  }catch(error){fs.writeFileSync(path.join(out,`${scenario}-failure.txt`),await page.locator('body').innerText());await page.screenshot({path:path.join(out,`${scenario}-failure.png`)});throw error}
  finally{await fixture.cleanup()}
 })
}
