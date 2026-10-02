import fs from 'node:fs'
import path from 'node:path'
import { test, expect } from '@playwright/test'
import { setupMockBackend, waitForAppReady } from './fixtures'
const out = process.env.THEME_PACKET!
for (const mode of ['light','dark'] as const) test(`default contract ${mode}`, async () => {
 test.setTimeout(180000)
 const f = await setupMockBackend({extraDisplayConfig: '  skin: default'})
 const page=f.page
 const chooser=async()=>{
  await page.getByRole('button',{name:'Open settings',exact:true}).click()
  await page.getByText('Appearance',{exact:true}).click()
  await page.getByText('Theme',{exact:true}).click()
  await expect(page.getByText('Desktop palettes only. The selected mode is applied on top.',{exact:true})).toBeVisible()
 }
 const capture=async(state:string,theme:string)=>{
  await expect(page.locator('html')).toHaveAttribute('data-hermes-theme',theme)
  await expect(page.locator('html')).toHaveAttribute('data-hermes-mode',mode)
  await expect(page.getByText('Desktop palettes only. The selected mode is applied on top.',{exact:true})).toBeVisible()
  await page.waitForTimeout(1500) // Allow Electron compositor + real route/theme animations to settle.
  await page.screenshot({animations:'disabled',path:path.join(out,`${mode}-${state}.png`)})
  const proof=await page.evaluate(()=>({theme:document.documentElement.dataset.hermesTheme,mode:document.documentElement.dataset.hermesMode,storedTheme:localStorage.getItem('hermes-desktop-theme-v2'),storedMode:localStorage.getItem('hermes-desktop-mode-v1'),registry:localStorage.getItem('hermes-desktop-backend-themes-v1'),events:(window as any).__themeProbe?.events??[],requests:(window as any).__themeProbe?.requests??[],responses:(window as any).__themeProbe?.responses??[],body:document.body.innerText,background:getComputedStyle(document.documentElement).getPropertyValue('--background'),skinCSS:!!document.querySelector('[data-hermes-skin-c-s-s]')}))
  fs.writeFileSync(path.join(out,`${mode}-${state}.json`),JSON.stringify({source:'e05b16348b1d06a3311237423b0a4fc30d9c5aa1',fixture:'theme-default-backend-real-v1',state,...proof},null,2))
 }
 try {
  await waitForAppReady(f)
  await page.getByRole('button',{name:'No thanks',exact:true}).click()
  await chooser()
  await page.getByRole('button',{name:mode==='light'?'Light':'Dark',exact:true}).click()
  await page.getByText('Mono',{exact:true}).click()
  await capture('manual-mono','mono')
  await page.addInitScript(()=>{
   const events:any[]=[],requests:any[]=[],responses:any[]=[]
   let socket:WebSocket|null=null
   const Original=window.WebSocket
   class Observed extends Original {
    constructor(...args:ConstructorParameters<typeof WebSocket>){super(...args);this.addEventListener('message',(event)=>{
     try {const frame=JSON.parse(String(event.data));const e=frame.params;
      if(frame.method==='event' && ['gateway.ready','skin.changed'].includes(e?.type)){socket=this;events.push({type:e.type,skin:e.type==='gateway.ready'?e.payload?.skin:e.payload})}
      if(String(frame.id).startsWith('theme-proof-'))responses.push(frame)
     }catch{}
    })}
   }
   window.WebSocket=Observed
   ;(window as any).__themeProbe={events,requests,responses,rpc(method:string,params:unknown){if(!socket)throw new Error('No observed ready socket');const frame={jsonrpc:'2.0',id:`theme-proof-${requests.length}`,method,params};requests.push(frame);socket.send(JSON.stringify(frame))}}
  })
  await page.reload()
  await expect(page.getByText('Desktop palettes only. The selected mode is applied on top.',{exact:true})).toBeVisible()
  await expect.poll(()=>page.evaluate(()=>(window as any).__themeProbe.events.some((e:any)=>e.type==='gateway.ready'&&e.skin?.name==='default'))).toBe(true)
  await capture('register-only-default-preserves-mono','mono')
  await page.evaluate(()=>(window as any).__themeProbe.rpc('config.set',{key:'skin',value:'default'}))
  await expect.poll(()=>page.evaluate(()=>(window as any).__themeProbe.events.some((e:any)=>e.type==='skin.changed'&&e.skin?.name==='default'))).toBe(true)
  await capture('explicit-default-applies-nous','nous')
  await page.evaluate(()=>(window as any).__themeProbe.rpc('config.get',{key:'skin'}))
  await expect.poll(()=>page.evaluate(()=>(window as any).__themeProbe.responses.length)).toBe(2)
  await page.getByText('Mono',{exact:true}).click()
  await page.evaluate(()=>(window as any).__themeProbe.rpc('config.set',{key:'skin',value:'default'}))
  await expect.poll(()=>page.evaluate(()=>(window as any).__themeProbe.responses.length)).toBe(3)
  await expect.poll(()=>page.evaluate(()=>(window as any).__themeProbe.events.filter((e:any)=>e.type==='skin.changed'&&e.skin?.name==='default').length)).toBe(2)
  await capture('repeated-default-preserves-manual-mono','mono')
 } finally { await f.cleanup() }
})
