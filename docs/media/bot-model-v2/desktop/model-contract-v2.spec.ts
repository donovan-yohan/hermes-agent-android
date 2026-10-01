import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { setupMockBackend, waitForAppReady } from './fixtures'
import { allowErrorBanners, expect, test } from './test'
const hash=(b:any)=>crypto.createHash('sha256').update(b).digest('hex')
const contract=process.env.PARITY_CONTRACT!
const out=process.env.PARITY_OUT!
const implementation=hash(fs.readFileSync(new URL(import.meta.url)))
for(const state of ['loaded','inventory-loading','inventory-error','manual','confirmation','saved','save-refused']) for(const theme of ['light','dark'] as const){
 test(`model v2 ${state} ${theme}`,async()=>{
  test.setTimeout(240000);allowErrorBanners();fs.mkdirSync(out,{recursive:true})
  const stem=`${state}-${theme}`
  const spec=JSON.parse(execFileSync('python3',[path.join(contract,'scripts/visual_parity_contract.py'),'describe','--surface','bot-model-config','--state',`bot-model-${state}`,'--theme',theme,'--fixture-id','bot-model-config-synthetic-v2','--platform','desktop'],{cwd:contract,encoding:'utf8'}))
  fs.writeFileSync(path.join(out,`${stem}.contract.json`),JSON.stringify(spec,null,2))
  const inputs=spec.synthetic_inputs,mapping=spec.platform_spec
  const fixture=await setupMockBackend();const {page}=fixture
  const actions:any[]=[];const events:string[]=[];let reopen_sequence:any;let discovery:any
  const save=(suffix:string,data:any)=>fs.writeFileSync(path.join(out,`${stem}.${suffix}.json`),JSON.stringify(data,null,2))
  async function nodes(loc:any){return await loc.evaluateAll((els:any[])=>els.flatMap(el=>[el,...el.querySelectorAll('*')]).filter((el:any)=>el.getClientRects().length).map((el:any)=>({text:(el.innerText||'').trim(),content_description:el.getAttribute('aria-label')||'',role:el.getAttribute('role')||el.tagName.toLowerCase(),...(el instanceof HTMLInputElement||el instanceof HTMLTextAreaElement?{value:el.value}:{})})).filter((x:any)=>x.text||x.content_description||x.value))}
  const safeNodes=(ns:any[])=>ns.filter(n=>n.text.length<500&&!/(?:\b(?:api[_-]?key|access[_-]?token|auth(?:orization)?|password|secret|credential)\b|\/(?:home|users|private|var|tmp)\/|~\/)/i.test([n.text,n.content_description,n.value??''].join(' ')))
  async function mark(event:string){return await page.evaluate(event=>{const p=(window as any).__modelV2;const sequence=++p.sequence;p.log.push({sequence,event});return sequence},event)}
  async function action(name:string,target:any,perform:()=>Promise<any>){await expect(target).toBeVisible();const observed=await nodes(target);expect(observed.length).toBeGreaterThan(0);const wire=await mark(`action:${name}`);await perform();actions.push({sequence:actions.length+1,action:name,origin:'recorded-ui-gestures',nodes:observed,wire_sequence:wire})}
  try{
   await waitForAppReady(fixture,120000)
   const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setTimezoneOverride',{timezoneId:'UTC'});await cdp.send('Emulation.setLocaleOverride',{locale:'en-US'})
   await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'})
   await page.evaluate(()=>{localStorage.setItem('hermes-desktop-theme-v2','mono');localStorage.setItem('hermes-desktop-mode-v1','system');localStorage.setItem('hermes-desktop-profile-themes-v1','{}');localStorage.setItem('hermes-desktop-profile-modes-v1','{}');window.dispatchEvent(new StorageEvent('storage',{key:'hermes-desktop-theme-v2',newValue:'mono'}))})
   await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
   await page.getByRole('button',{name:'New bot or group chat'}).click();await page.getByRole('menuitem',{name:/^New bot$/i}).click()
   const create=page.getByRole('dialog',{name:/^New bot$/i});await create.getByPlaceholder('inbox-triage').fill(inputs.name);await create.getByPlaceholder('Inbox Triage').fill(inputs.title)
   const textareas=create.locator('textarea');if(await textareas.count())await textareas.first().fill(inputs.description)
   await create.getByRole('button',{name:/^Create bot$/i}).click();await expect(create).toBeHidden({timeout:60000})
   // Seed exact description via real editor: creation prefixes the title.
   await page.locator(`[data-roster-key="local::${inputs.name}"]`).click({button:'right'});await page.getByRole('menuitem',{name:/^Edit/}).first().click()
   const seedEditor=page.getByRole('dialog',{name:'Edit profile',exact:true});await seedEditor.locator('textarea').first().fill(inputs.description);await seedEditor.getByRole('button',{name:'Save',exact:true}).click();await expect(seedEditor).toBeHidden()
   await page.addInitScript(({state,inputs})=>{
    const w=window as any;const p=w.__modelV2={sequence:0,log:[] as any[],saved:{provider:inputs.provider,default:inputs.initial_model},pending:null as any,violations:[] as any[]}
    const scopes=new WeakSet<WebSocket>(),send=WebSocket.prototype.send
    WebSocket.prototype.send=function(text){
     let f:any;try{f=JSON.parse(String(text))}catch{return send.call(this,text)}
     const a=f.params||{}
     if(f.method==='profiles.describe'&&a.name===inputs.name)scopes.add(this)
     const scoped=scopes.has(this)&&(a.profile===undefined||a.profile===inputs.name)&&(a.name===undefined||a.name===inputs.name)
     if(!scoped||!['profiles.describe','model.options','profiles.configure','mcp.catalog'].includes(f.method))return send.call(this,text)
     if(f.method==='profiles.configure'&&a.model===undefined&&a.provider===undefined)return send.call(this,text)
     const item:any={sequence:++p.sequence,method:f.method,params:a,request_id:String(f.id),scope:{kind:'bot-scoped-websocket',profile:inputs.name},response:null,error:null};p.log.push(item)
     let result:any
     if(f.method==='profiles.describe')result={model:{...p.saved},soul:inputs.soul,skills:[],toolsets:[],mcp_servers:[]}
     if(f.method==='mcp.catalog')result={servers:[]}
     if(f.method==='model.options'){
      if(state==='inventory-loading'){item.outcome='pending';p.pending={request_id:String(f.id),start:performance.now(),pending:true,response:null,error:null};return}
      if(state==='inventory-error')item.error={code:-32000,message:'Synthetic inventory refusal'}
      else result={providers:[{slug:inputs.provider,name:inputs.provider_name,models:[inputs.initial_model,inputs.requested_model]}]}
     }
     if(f.method==='profiles.configure'){
      const keys=Object.keys(a).filter(k=>k!=='profile').sort();const expected=a.confirm_expensive_model?['confirm_expensive_model','model','name','provider']:['model','name','provider']
      if(JSON.stringify(keys)!==JSON.stringify(expected)||a.name!==inputs.name||a.provider!==inputs.provider||a.model!==inputs.requested_model)p.violations.push(a)
      if(state==='save-refused')item.error={code:-32000,message:'Synthetic model save refused'}
      else if(!a.confirm_expensive_model)result={ok:true,confirm_required:true,confirm_message:inputs.confirmation_message,applied:{model:false}}
      else{p.saved={provider:a.provider,default:a.model};result={ok:true,applied:{model:true}}}
     }
     item.response=result??null;item.outcome=item.error?'refused':f.method==='profiles.configure'?(result.confirm_required?'warning':'applied'):'loaded'
     queueMicrotask(()=>this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({jsonrpc:'2.0',id:f.id,...(item.error?{error:item.error}:{result})})})))
    }
   },{state,inputs})
   await page.reload();await waitForAppReady(fixture,120000);await page.clock.setFixedTime(new Date('2026-09-17T16:00:00Z'))
   await page.getByRole('button',{name:'Bots',exact:true}).or(page.getByRole('tab',{name:'Bots',exact:true})).first().click()
   const row=page.locator(`[data-roster-key="local::${inputs.name}"]`),editor=page.getByRole('dialog',{name:'Edit profile',exact:true})
   async function open(record=true){
    await row.click({button:'right'});const edit=page.getByRole('menuitem',{name:/^Edit/}).first()
    if(record)await action('roster:edit',edit,async()=>{reopen_sequence=await mark('roster:edit-dispatch');await edit.click()})
    else{reopen_sequence=await mark('roster:edit-dispatch');await edit.click()}
    const adv=editor.getByRole('button',{name:/^Advanced/});if(record)await action('editor:advanced',adv,()=>adv.click());else await adv.click()
   }
   await open()
   await expect.poll(()=>page.evaluate(()=>(window as any).__modelV2.log.some((x:any)=>x.method==='model.options'))).toBe(true)
   if(['loaded','inventory-loading','inventory-error','manual'].includes(state))events.push('describe:loaded')
   const provider=editor.getByRole('combobox').filter({hasText:inputs.provider_name})
   if(state==='inventory-loading')events.push('model.options:pending')
   else if(state==='inventory-error'){
    await expect(editor.getByPlaceholder('omnirouter / 9router / nous …')).toHaveValue(inputs.provider);events.push('model.options:refused','manual-fallback:visible')
   }else{
    await expect(provider).toBeVisible();if(['loaded','manual'].includes(state))events.push('model.options:loaded')
    if(state==='manual'){
     await action('provider:open-menu',provider,()=>provider.click());const manual=page.getByRole('option',{name:/Enter manually/});await action('provider:enter-manually',manual,()=>manual.click());await expect(editor.getByPlaceholder('omnirouter / inferx / 9router')).toHaveValue(inputs.provider);events.push('manual-entry:clicked')
    }
    if(['confirmation','saved','save-refused'].includes(state)){
     const model=editor.getByRole('combobox').filter({hasText:inputs.initial_model})
     await action('model:change',model,async()=>{await model.click();await page.getByRole('option',{name:inputs.requested_model,exact:true}).click()});events.push('model-edit:changed')
     const saveButton=editor.getByRole('button',{name:'Save',exact:true});await action('editor:save',saveButton,()=>saveButton.click());events.push('save:clicked')
     if(state==='save-refused'){
      await expect(page.getByText('Synthetic model save refused',{exact:false})).toBeVisible();events.push('configure:initial-write-refused')
      const text=page.getByText('Synthetic model save refused',{exact:false});save('refusal-dom',await text.evaluateAll(es=>es.map(e=>({html:e.outerHTML,parent:e.parentElement?.outerHTML,grandparent:e.parentElement?.parentElement?.outerHTML}))))
      // Derive the smallest actual native notice container from observed ancestry.
      const noticeInfo=await text.evaluate(el=>{let e:Element|null=el;while(e){if(e.matches('[data-sonner-toast], [role="alert"], [role="status"]'))return {tag:e.tagName,role:e.getAttribute('role'),sonner:e.hasAttribute('data-sonner-toast')};e=e.parentElement}return null})
      if(!noticeInfo)throw new Error('Native initial refusal observed but no unique native notice boundary discovered; inspect refusal-dom')
      const locator=noticeInfo.sonner?{kind:'css',value:'[data-sonner-toast]'}:{kind:'css',value:`[role="${noticeInfo.role}"]`}
      const notice=page.locator(locator.value);await expect(notice).toHaveCount(1);await expect(notice).toBeVisible()
      const noticeNodes=await nodes(notice);const png=await notice.screenshot({path:path.join(out,`${stem}.png`),animations:'disabled'})
      await expect(editor).toBeHidden();discovery={phase:'initial-write-refused',editor_closed:true,artifacts:[{kind:'initial-refusal-notice',locator,locator_matches:1,screenshot_sha256:hash(png),nodes:noticeNodes}]}
      save('notice-dismiss-nodes',await nodes(notice.getByRole('button')));await mark('notice:dismiss-after-primary-capture');await notice.getByRole('button').click();await expect(notice).toBeHidden()
      await open(false);await expect(editor.getByRole('combobox').filter({hasText:inputs.initial_model})).toBeVisible()
      await editor.getByRole('button',{name:/^Advanced/}).evaluate(el=>el.scrollIntoView({block:'start'}))
      const reopened=await editor.screenshot({path:path.join(out,`${stem}.refused-reopened.png`),animations:'disabled'})
      const reads=await page.evaluate(()=>(window as any).__modelV2.log.filter((x:any)=>x.method==='profiles.describe'))
      discovery.artifacts.push({kind:'refused-reopened',locator:{kind:'role',role:'dialog',name:'Edit profile',exact:true},locator_matches:await editor.count(),screenshot_sha256:hash(reopened),nodes:await nodes(editor),fields:{provider:inputs.provider,model:inputs.initial_model},reopen_sequence,describe_read:{sequence:reads.at(-1).sequence,profile:inputs.name,model:reads.at(-1).response.model.default}})
     }else{
      const confirm=page.getByRole('dialog',{name:`Switch to ${inputs.requested_model}?`,exact:true});await expect(confirm).toBeVisible();await expect(confirm.getByText(inputs.confirmation_message,{exact:true})).toBeVisible();events.push('configure:confirmation-required')
      if(state==='confirmation')events.push('shared-confirm-dialog:visible')
      else{const button=confirm.getByRole('button',{name:/Switch anyway|Use model|Switch model/});await action('shared-dialog:confirm',button,()=>button.click());events.push('confirm:clicked');await expect(editor).toBeHidden();events.push('configure:applied','editor:closed');await open();events.push('editor:reopened');await expect(editor.getByRole('combobox').filter({hasText:inputs.requested_model})).toBeVisible();events.push('describe:saved-pair')}
     }
    }
   }
   await expect(page.locator('html')).toHaveAttribute('data-hermes-mode',theme);await expect(page.locator('html')).toHaveAttribute('data-hermes-theme','mono')
   const normalization=await page.evaluate(()=>({skin:document.documentElement.dataset.hermesTheme,theme:document.documentElement.dataset.hermesMode,locale:Intl.DateTimeFormat().resolvedOptions().locale,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,clock:new Date().toISOString()}));expect(normalization.locale).toBe('en-US');expect(normalization.timezone).toBe('UTC');expect(normalization.clock).toBe('2026-09-17T16:00:00.000Z');save('normalization',normalization)
   const target=state==='confirmation'?page.getByRole('dialog',{name:`Switch to ${inputs.requested_model}?`,exact:true}):editor
   if(!['confirmation','save-refused'].includes(state))await editor.getByRole('button',{name:/^Advanced/}).evaluate(el=>el.scrollIntoView({block:'start'}))
   await expect(target).toHaveCount(1);await expect(target).toBeVisible()
   const pending=()=>page.evaluate(()=>{const p=(window as any).__modelV2.pending;return {request_id:p.request_id,pending:p.pending,response:p.response,error:p.error,elapsed_ms:performance.now()-p.start}})
   const before=state==='inventory-loading'?await pending():null
   let png=state==='save-refused'?fs.readFileSync(path.join(out,`${stem}.png`)):await target.screenshot({path:path.join(out,`${stem}.png`),animations:'disabled'})
   const after=state==='inventory-loading'?await pending():null
   if(after){expect(after.elapsed_ms).toBeLessThan(20000);expect(after.pending).toBe(true)}
   const observed=state==='save-refused'?discovery.artifacts[0].nodes:await nodes(target)
   const p=await page.evaluate(()=>(window as any).__modelV2);save('rpc',p);save('actions',actions);expect(p.violations).toEqual([])
   const calls=p.log.filter((x:any)=>x.method==='profiles.configure').map((x:any)=>({sequence:x.sequence,profile:x.params.name,patch:{provider:x.params.provider,model:x.params.model},confirmed:x.params.confirm_expensive_model===true,outcome:x.outcome,response:x.response}))
   expect(calls.length).toBe(mapping.assertions.model_write_count);expect(p.saved.default).toBe(mapping.assertions.authoritative_model)
   const reads=p.log.filter((x:any)=>x.method==='profiles.describe').map((x:any)=>({sequence:x.sequence,profile:x.params.name,model:x.response.model.default}))
   const inventory=p.log.find((x:any)=>x.method==='model.options');expect(inventory.params).toMatchObject({include_unconfigured:true,explicit_only:false});expect(inventory.scope).toEqual(spec.transport_mapping.desktop)
   let fields:any=null
   if(!['confirmation','save-refused','inventory-loading'].includes(state)){
    const values=await target.locator('input,textarea,[role="combobox"]').evaluateAll(es=>es.map((e:any)=>e instanceof HTMLInputElement||e instanceof HTMLTextAreaElement?e.value:e.innerText));save('fields-observed',values)
    expect(values.some((x:string)=>x===inputs.provider||x.includes(inputs.provider_name))).toBe(true);expect(values.some((x:string)=>x===p.saved.default||x.includes(p.saved.default))).toBe(true)
    fields={provider:inputs.provider,model:p.saved.default}
   }
   const proof:any={source:'runtime-capture-worker',...Object.fromEntries(['selector','locator','presentation','interaction_semantics','action_origin','ordered_actions'].map(k=>[k,mapping[k]])),events,nodes:observed,locator_matches:1,action_evidence:actions,model_calls:calls,authoritative_model:p.saved.default,fields,describe_reads:reads,inventory:{method:'model.options',scope:inventory.scope,outcome:inventory.outcome}}
   if(state==='manual'||state==='inventory-error')proof.manual_fields_visible=true
   if(state==='saved')proof.reopen_sequence=reopen_sequence
   if(before)proof.loading_bracket={screenshot_sha256:hash(png),request_id:before.request_id,basis:'monotonic-since-interception',before,after}
   save('raw-nodes',observed)
   if(discovery){save('raw-discovery',discovery);discovery.artifacts=discovery.artifacts.map((a:any)=>({...a,nodes:safeNodes(a.nodes)}));proof.discovery=discovery}
   proof.nodes=safeNodes(observed)
   const receipt={schema_version:1,surface:'bot-model-config',state:`bot-model-${state}`,fixture_id:'bot-model-config-synthetic-v2',theme,viewport:await page.evaluate(()=>({width:innerWidth,height:innerHeight})),desktop_upstream_sha:spec.desktop_sha,fixture_origin:'pinned-desktop-e2e-mock',capture_mapping:mapping,capture_inputs:{...spec.capture_inputs,theme},synthetic_inputs:inputs,screenshot_sha256:hash(png),fixture_implementation_sha256:implementation,state_proof:proof}
   save('receipt',receipt)
   const validation=execFileSync('python3',[path.join(contract,'scripts/visual_parity_contract.py'),'check-receipt','--platform','desktop','--receipt',path.join(out,`${stem}.receipt.json`)],{cwd:contract,encoding:'utf8'});fs.writeFileSync(path.join(out,`${stem}.validation.txt`),validation)
  }catch(error){fs.writeFileSync(path.join(out,`${stem}.failure.txt`),String(error)+'\n'+await page.locator('body').innerText());save('failure-rpc',await page.evaluate(()=>(window as any).__modelV2??null));save('failure-actions',actions);throw error}
  finally{await fixture.cleanup()}
 })
}
