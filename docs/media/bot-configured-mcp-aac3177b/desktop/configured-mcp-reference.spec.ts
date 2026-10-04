import fs from 'node:fs'
import path from 'node:path'
import { setupMockBackend, waitForAppReady } from './fixtures'
import { allowErrorBanners, expect, test } from './test'

for (const scenario of ['disabled','empty','loading','error']) {
 test(`configured MCP actual Connectors ${scenario}`, async () => {
  test.setTimeout(240_000); allowErrorBanners()
  const fixture=await setupMockBackend();const {page,app}=fixture
  const out=process.env.PARITY_OUT!;fs.mkdirSync(out,{recursive:true})
  try {
   await waitForAppReady(fixture,120_000)
   const cdp=await page.context().newCDPSession(page)
   await cdp.send('Emulation.setTimezoneOverride',{timezoneId:'UTC'})
   await cdp.send('Emulation.setLocaleOverride',{locale:'en-US'})
   await page.emulateMedia({colorScheme:'light',reducedMotion:'reduce'})
   await page.evaluate(()=>{
    localStorage.setItem('hermes-desktop-theme-v2','mono');localStorage.setItem('hermes-desktop-mode-v1','system')
    localStorage.setItem('hermes-desktop-profile-themes-v1','{}');localStorage.setItem('hermes-desktop-profile-modes-v1','{}')
   })
   await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
   await page.getByRole('button',{name:'New bot or group chat'}).click()
   await page.getByRole('menuitem',{name:/^New bot$/i}).click()
   const create=page.getByRole('dialog',{name:/^New bot$/i})
   await create.getByPlaceholder('inbox-triage').fill('synthetic-mcp')
   await create.getByPlaceholder('Inbox Triage').fill('Synthetic MCP')
   await create.getByRole('button',{name:/^Create bot$/i}).click();await expect(create).toBeHidden({timeout:60_000})
   await app.evaluate(({ipcMain},scenario)=>{
    const original=(ipcMain as any)._invokeHandlers.get('hermes:api')
    const probe=(globalThis as any).__mcpCapture={scenario,log:[],pendingReads:0,pendingWrites:0,release:null,servers:scenario==='empty'?{}:{'synthetic-research':{command:'synthetic-mcp-command',args:['--synthetic'],enabled:false},'synthetic-notes':{url:'https://example.invalid/mcp',enabled:false}}}
    ipcMain.removeHandler('hermes:api')
    ipcMain.handle('hermes:api',async(event,request)=>{
     if(request.profile!=='synthetic-mcp')return original(event,request)
     if(!request.path.startsWith('/api/mcp')&&!request.path.startsWith('/api/config'))return original(event,request)
     const log={request:JSON.parse(JSON.stringify(request)),phase:'admitted',at:Date.now(),result:null as any};probe.log.push(log)
     if(request.path==='/api/config'||request.path==='/api/config?include_defaults=false'){
      if(request.method && request.method!=='GET')throw Error('Unexpected config mutation')
      if(scenario==='loading'){probe.pendingReads++;return new Promise(()=>{})}
      if(scenario==='error'){log.phase='refused';throw Error('Synthetic configuration read refusal')}
      log.phase='read';log.result={mcp_servers:JSON.parse(JSON.stringify(probe.servers))};return log.result
     }
     if(request.path.startsWith('/api/mcp/catalog')){log.phase='read';log.result={entries:[]};return log.result}
     if(request.path==='/api/mcp/servers'&&request.method==='PUT'){
      const next=request.body.servers
      if(Object.keys(next).sort().join(',')!=='synthetic-notes,synthetic-research'||next['synthetic-research'].enabled===false||next['synthetic-notes'].enabled!==false)throw Error('Unexpected whole-map mutation')
      probe.pendingWrites++;await new Promise(resolve=>{probe.release=resolve})
      probe.servers=JSON.parse(JSON.stringify(next));probe.pendingWrites--;log.phase='committed';log.result={ok:true};return log.result
     }
     if(request.path==='/api/mcp/servers/synthetic-research/test'&&request.method==='POST'){
      log.phase='synthetic-probe';log.result={ok:true,tools:[{name:'synthetic_lookup',description:'Synthetic tool response'}]};return log.result
     }
     log.phase='blocked';throw Error('Unregistered synthetic MCP endpoint')
    })
   },scenario)
   await page.addInitScript(()=>{
    ;(window as any).__mcpRpc=[]
    const send=WebSocket.prototype.send
    WebSocket.prototype.send=function(data){try{const p=JSON.parse(String(data));if(p.method==='reload.mcp')(window as any).__mcpRpc.push({method:p.method,params:p.params,at:performance.now()})}catch{}return send.call(this,data)}
   })
   await page.reload();await waitForAppReady(fixture,120_000)
   await page.clock.setFixedTime(new Date('2026-09-17T16:00:00Z'))
   await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
   const editor=page.getByRole('dialog',{name:'Edit profile'})
   async function open(){
    await page.locator('[data-roster-key="local::synthetic-mcp"]').click({button:'right'})
    await page.getByRole('menuitem',{name:/^Edit/}).first().click()
    await editor.getByRole('button',{name:/^Advanced/}).click()
    await editor.getByRole('button',{name:/^Connectors(?:\s|$)/}).first().click()
    await editor.getByRole('button',{name:/^Advanced/}).evaluate(el=>el.scrollIntoView({block:'start'}))
   }
   const observe=async()=>({probe:await app.evaluate(()=>{const p=(globalThis as any).__mcpCapture;return {...p,release:undefined}}),rpc:await page.evaluate(()=>(window as any).__mcpRpc)})
   async function capture(state:string){
    for(const theme of ['light','dark'] as const){
     await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'})
     await expect(page.locator('html')).toHaveAttribute('data-hermes-mode',theme)
     await expect(page.locator('html')).toHaveAttribute('data-hermes-theme','mono')
     const before=await observe()
     await editor.screenshot({path:path.join(out,`${state}-${theme}.png`),animations:'disabled'})
     const after=await observe()
     const normalization=await page.evaluate(()=>({skin:document.documentElement.dataset.hermesTheme,mode:document.documentElement.dataset.hermesMode,locale:Intl.DateTimeFormat().resolvedOptions().locale,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,clock:new Date().toISOString()}))
     fs.writeFileSync(path.join(out,`${state}-${theme}.json`),JSON.stringify({state,theme,selector:'[role=dialog][aria-labelledby] Edit profile',normalization,text:await editor.innerText(),nodes:await editor.ariaSnapshot(),before,after,transport:'actual renderer API through isolated main hermes:api synthetic interception; actual websocket send observation'},null,2))
    }
   }
   await open()
   if(scenario==='disabled'){
    const target=editor.getByRole('switch',{name:/Synthetic Research/})
    await expect(target).toBeVisible();await expect(target).not.toBeChecked();await capture('disabled')
    fs.writeFileSync(path.join(out,'toggle-action.json'),JSON.stringify({origin:'Playwright real switch click',before:await target.ariaSnapshot(),bounds:await target.boundingBox(),transportBefore:await observe()},null,2))
    await target.click();await expect.poll(()=>app.evaluate(()=>(globalThis as any).__mcpCapture.pendingWrites)).toBe(1)
    await capture('pending')
    await app.evaluate(()=>(globalThis as any).__mcpCapture.release())
    await expect(target).toBeChecked()
    await expect.poll(async()=> (await observe()).probe.log.filter((l:any)=>l.phase==='synthetic-probe').length).toBeGreaterThan(0)
    await capture('saved')
    await editor.getByRole('button',{name:'Cancel',exact:true}).click();await open();await expect(target).toBeChecked();await capture('reopened')
   }else{
    if(scenario==='loading')await expect.poll(()=>app.evaluate(()=>(globalThis as any).__mcpCapture.pendingReads)).toBeGreaterThan(0)
    if(scenario==='error'){
     await expect.poll(async()=> (await observe()).probe.log.filter((l:any)=>l.phase==='refused').length).toBeGreaterThan(0)
     await expect(editor.getByText('Reading the catalog and the servers on this computer',{exact:true})).toBeHidden({timeout:30_000})
    }
    await capture(scenario==='error'?'read-refused-settled':scenario)
   }
  }catch(error){fs.writeFileSync(path.join(out,`${scenario}-failure.txt`),await page.locator('body').innerText());await page.screenshot({path:path.join(out,`${scenario}-failure.png`)});throw error}
  finally{await fixture.cleanup()}
 })
}
