import { Buffer } from 'node:buffer'
import fs from 'node:fs'
import path from 'node:path'
import { setupMockBackend, waitForAppReady } from './fixtures'
import { allowErrorBanners, expect, test } from './test'

for (const scenario of ['pending']) test(`avatar actual ${scenario}`, async()=>{
 test.setTimeout(240_000); allowErrorBanners()
 const fixture=await setupMockBackend(); const {page}=fixture
 const out=process.env.PARITY_OUT!; fs.mkdirSync(out,{recursive:true})
 try {
  await waitForAppReady(fixture,120_000)
  await fixture.app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(1440,1100))
  await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
  await page.getByRole('button',{name:'New bot or group chat'}).click()
  await page.getByRole('menuitem',{name:/^New bot$/i}).click()
  const create=page.getByRole('dialog',{name:/^New bot$/i})
  await create.getByPlaceholder('inbox-triage').fill('synthetic-avatar')
  await create.getByPlaceholder('Inbox Triage').fill('Synthetic Avatar')
  await create.getByRole('button',{name:/^Create bot$/i}).click()
  await expect(create).toBeHidden({timeout:60_000})
  const asset=path.join(fixture.sandbox.hermesHome,'profiles','synthetic-avatar','assets','avatar.png')
  fs.mkdirSync(path.dirname(asset),{recursive:true});fs.copyFileSync(path.join(out,'blue.png'),asset)
  await page.addInitScript(({scenario})=>{
   const w=window as any;w.__avatarProbe={reads:0,writes:[],pending:0,refused:0,frames:[],responses:[]}
   const send=WebSocket.prototype.send
   WebSocket.prototype.send=function(data){
    let f:any;try{f=JSON.parse(String(data))}catch{return send.call(this,data)}
    if(f.method?.startsWith('profiles.')){w.__avatarProbe.frames.push(f);this.addEventListener('message',(event)=>{try{const r=JSON.parse(String(event.data));if(r.id===f.id)w.__avatarProbe.responses.push(r)}catch{}},{once:false})}
    if(f.params?.name==='synthetic-avatar'&&f.params?.asset==='avatar'){
     if(f.method==='profiles.set_asset')w.__avatarProbe.writes.push({clear:f.params.clear===true,data:f.params.data})
     if(f.method==='profiles.get_asset'){
      w.__avatarProbe.reads++
      if(scenario==='pending'){w.__avatarProbe.pending++;return}
      if(scenario==='error'){w.__avatarProbe.refused++;queueMicrotask(()=>this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({jsonrpc:'2.0',id:f.id,error:{code:-32000,message:'Synthetic avatar read refusal'}})})));return}
     }
    }
    return send.call(this,data)
   }
  },{scenario})
  await page.reload();await waitForAppReady(fixture,120_000)
  const cdp=await page.context().newCDPSession(page)
  await cdp.send('Emulation.setTimezoneOverride',{timezoneId:'UTC'});await cdp.send('Emulation.setLocaleOverride',{locale:'en-US'})
  await page.evaluate(()=>{localStorage.setItem('hermes-desktop-theme-v2','mono');localStorage.setItem('hermes-desktop-mode-v1','system');localStorage.setItem('hermes-desktop-profile-themes-v1','{}');localStorage.setItem('hermes-desktop-profile-modes-v1','{}');window.dispatchEvent(new StorageEvent('storage',{key:'hermes-desktop-theme-v2',newValue:'mono'}))})
  await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
  const editor=page.getByRole('dialog',{name:'Edit profile'})
  async function open(){await page.locator('[data-roster-key="local::synthetic-avatar"]').click({button:'right'});await page.getByRole('menuitem',{name:/^Edit/}).first().click();await expect(editor).toBeVisible()}
  async function capture(state:string){
   for(const theme of ['light','dark'] as const){
    await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'});await expect(page.locator('html')).toHaveAttribute('data-hermes-mode',theme);await expect(page.locator('html')).toHaveAttribute('data-hermes-theme','mono')
    await editor.screenshot({path:path.join(out,`desktop-${state}-${theme}.png`),animations:'disabled'})
    fs.writeFileSync(path.join(out,`desktop-${state}-${theme}.json`),JSON.stringify({state,theme,text:await editor.innerText(),images:await editor.locator('img').evaluateAll(xs=>xs.map(x=>({src:(x as HTMLImageElement).src,width:(x as HTMLImageElement).naturalWidth,height:(x as HTMLImageElement).naturalHeight}))),probe:await page.evaluate(()=>(window as any).__avatarProbe)},null,2))
   }
  }
  await expect.poll(()=>page.evaluate(()=>(window as any).__avatarProbe.reads),{timeout:30_000}).toBeGreaterThan(0)
  await open()
  if(scenario!=='journey'){
   await expect.poll(()=>page.evaluate((s)=>(window as any).__avatarProbe[s==='pending'?'pending':'refused'],scenario)).toBeGreaterThan(0)
   await capture(scenario==='pending'?'read-pending-fallback':'read-error-fallback');return
  }
  await expect(editor.locator('img').first()).toBeVisible();await capture('loaded')
  await editor.getByRole('button',{name:'Upload',exact:true}).click()
  const pick=page.waitForEvent('filechooser');await editor.getByRole('button',{name:/Choose an image/i}).click();const chooser=await pick
  await capture('picking-app-unchanged')
  await chooser.setFiles([]);await capture('cancel-no-selection')
  expect(await page.evaluate(()=>(window as any).__avatarProbe.writes.length)).toBe(0)
  const next=page.waitForEvent('filechooser');await editor.getByRole('button',{name:/Choose an image/i}).click();await (await next).setFiles(path.join(out,'orange.png'))
  await expect.poll(()=>editor.locator('img').first().evaluate((el)=>{const c=document.createElement('canvas');c.width=c.height=1;const x=c.getContext('2d')!;x.drawImage(el as HTMLImageElement,0,0,1,1);return [...x.getImageData(0,0,1,1).data].slice(0,3)})).toEqual([220,140,60])
  await expect.poll(()=>editor.locator('img').first().evaluate((el)=>(el as HTMLImageElement).naturalWidth)).toBe(256)
  await capture('changed-staged')
  await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden()
  await expect.poll(()=>page.evaluate(()=>(window as any).__avatarProbe.writes.length)).toBe(1)
  const upload=await page.evaluate(()=>(window as any).__avatarProbe.writes[0].data)
  expect(fs.readFileSync(asset).equals(Buffer.from(upload.split(',')[1],'base64'))).toBe(true)
  fs.writeFileSync(path.join(out,'saved-disk-outcome.json'),JSON.stringify({exactUploadBytesOnDisk:true,bytes:fs.statSync(asset).size},null,2))
  await open();await expect(editor.locator('img').first()).toBeVisible();await capture('saved-reopened')
  await editor.getByRole('button',{name:/Remove image/}).click();await expect(editor.locator('img')).toHaveCount(0)
  await editor.getByRole('button',{name:'Save',exact:true}).click();await expect(editor).toBeHidden()
  await expect.poll(()=>page.evaluate(()=>(window as any).__avatarProbe.writes.filter((x:any)=>x.clear).length)).toBe(1)
  expect(await page.evaluate(()=>(window as any).__avatarProbe.writes[1].clear)).toBe(true)
  await open();await capture('cleared-reopened-shape')
  fs.writeFileSync(path.join(out,'clear-disk-outcome.json'),JSON.stringify({assetExists:fs.existsSync(asset),note:'Upstream pushLocalAvatars can rasterize the cleared shape and repopulate avatar.png; this is not Android confirmed asset absence.',writes:await page.evaluate(()=>(window as any).__avatarProbe.writes)},null,2))
 }catch(e){fs.writeFileSync(path.join(out,'diagnostic.json'),JSON.stringify({probe:await page.evaluate(()=>(window as any).__avatarProbe),seedExists:fs.existsSync(path.join(fixture.sandbox.hermesHome,'profiles','synthetic-avatar','assets','avatar.png'))},null,2));fs.writeFileSync(path.join(out,`${scenario}-failure.txt`),await page.locator('body').innerText());throw e}
 finally{await fixture.cleanup()}
})
