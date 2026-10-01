import fs from 'node:fs'
import path from 'node:path'
import { setupMockBackend, waitForAppReady } from './fixtures'
import { allowErrorBanners, expect, test } from './test'
test('routine read refusal real component',async()=>{
 test.setTimeout(180_000);allowErrorBanners()
 const fixture=await setupMockBackend();const {page}=fixture
 try {
  await waitForAppReady(fixture,120_000)
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'UTC' })
  await cdp.send('Emulation.setLocaleOverride', { locale: 'en-US' })
  await page.evaluate(() => {
    localStorage.setItem('hermes-desktop-theme-v2', 'mono')
    localStorage.setItem('hermes-desktop-mode-v1', 'system')
    localStorage.setItem('hermes-desktop-profile-themes-v1', '{}')
    localStorage.setItem('hermes-desktop-profile-modes-v1', '{}')
    window.dispatchEvent(new StorageEvent('storage', {key:'hermes-desktop-theme-v2',newValue:'mono'}))
  })
  await expect(page.locator('html')).toHaveAttribute('data-hermes-theme','mono')
  expect(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone)).toBe('UTC')
  expect(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().locale)).toBe('en-US')

  await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
  await page.getByRole('button',{name:'New bot or group chat'}).click()
  await page.getByRole('menuitem',{name:/^New bot$/i}).click()
  const dialog=page.getByRole('dialog',{name:/^New bot$/i})
  await dialog.getByPlaceholder('inbox-triage').fill('ops')
  await dialog.getByPlaceholder('Inbox Triage').fill('Ops')
  await dialog.getByRole('button',{name:/^Create bot$/i}).click()
  await expect(dialog).toBeHidden({timeout:60_000})
  await page.evaluate(()=>{
   const send=WebSocket.prototype.send;(window as any).__refusals=0
   WebSocket.prototype.send=function(text){
    let frame:any;try{frame=JSON.parse(String(text))}catch{return send.call(this,text)}
    if(frame.method!=='cron.manage')return send.call(this,text)
    ;(window as any).__refusals++
    queueMicrotask(()=>this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({jsonrpc:'2.0',id:frame.id,error:{code:-32000,message:'Synthetic read refusal'}})})))
   }
  })
  await page.locator('[data-roster-key="local::ops"]').click()
  await page.getByRole('tab',{name:'Scheduled jobs'}).first().click()
  await expect(page.getByText('The list may still be there — this was a read failure, not a delete.',{exact:true})).toBeVisible({timeout:40_000})
  await expect.poll(()=>page.evaluate(()=>(window as any).__refusals)).toBeGreaterThan(0)
  for(const theme of ['dark','light'] as const){
   await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'})
   await expect(page.locator('html')).toHaveAttribute('data-hermes-mode',theme)
   
   await expect(page.locator('html')).toHaveAttribute('data-hermes-theme','mono')
   const normalized=await page.evaluate(()=>({skin:document.documentElement.dataset.hermesTheme,mode:document.documentElement.dataset.hermesMode,locale:Intl.DateTimeFormat().resolvedOptions().locale,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,clock:new Date().toISOString()}))
   expect(normalized.locale).toBe('en-US');expect(normalized.timezone).toBe('UTC')

   await page.clock.setFixedTime(new Date('2026-09-17T16:00:00Z'))
   normalized.clock=await page.evaluate(()=>new Date().toISOString())
   expect(normalized.clock).toBe('2026-09-17T16:00:00.000Z')
   await expect(page.getByRole('button',{name:'Retry',exact:true})).toBeVisible()
   fs.writeFileSync(path.join(process.env.PARITY_OUT!,`read-failure-${theme}.normalization.json`),JSON.stringify({...normalized,refusals:await page.evaluate(()=>(window as any).__refusals)},null,2))
   await page.screenshot({path:path.join(process.env.PARITY_OUT!,`read-failure-${theme}.png`),animations:'disabled'})
   fs.writeFileSync(path.join(process.env.PARITY_OUT!,`read-failure-${theme}.txt`),await page.locator('body').innerText())
  }
 }finally{await fixture.cleanup()}
})
