/// <reference types="vite/client" />
// Export-local synthetic test boundary. No live gateway, credentials, or jobs.
import React, { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { useStore } from '@nanostores/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './src/styles.css'

const params = new URLSearchParams(location.search)
const theme = params.get('theme') === 'light' ? 'light' : 'dark'
const state = params.get('state') ?? 'populated'
localStorage.setItem('hermes-desktop-theme-v2', 'mono')
localStorage.setItem('hermes-desktop-mode-v1', theme)
const fixedNow = new Date('2026-10-19T09:00:00Z').getTime()
const NativeDate = Date
class FixtureDate extends NativeDate {
  constructor(...args: any[]) { if (args.length === 0) super(fixedNow); else super(...args as [any]) }
  static now() { return fixedNow }
}
window.Date = FixtureDate as DateConstructor
const base = { profile:'default', enabled:true, state:'scheduled', last_run_at:null, next_run_at:'2026-10-20T09:00:00Z', last_error:null, deliver:'local' }
let jobs: any[] = state === 'empty' || state === 'load-error' ? [] : [
 { ...base, id:'report-daily', name:'Status report', prompt:'Prepare a status report', schedule:{expr:'*/15 * * * *', display:'Every 15 minutes'}, schedule_display:'Every 15 minutes' },
 { ...base, id:'script-check', name:'Script check', prompt:'', script:'printf scheduled', no_agent:true, schedule:{expr:'0 9 * * *', display:'Every day at 9:00 AM'}, schedule_display:'Every day at 9:00 AM' }
]
if (state === 'job-error') jobs[0] = {...jobs[0], last_error:'Synthetic fixture: scheduled task failed'}
const runs = [
 {id:'local-cron-active',profile:'default',source:'cron',title:'Status report — active',preview:'Synthetic scheduler-owned run',started_at:1792396800,last_active:1792396800,ended_at:null,scheduler_owned:true,is_active:true},
 {id:'local-cron-completed',profile:'default',source:'cron',title:'Status report',preview:'Synthetic completed run',started_at:1792342800,last_active:1792342860,ended_at:1792342860,scheduler_owned:false,is_active:false}
]
const scriptRuns = [{id:'synthetic-script-output',profile:'default',source:'cron_output',title:'scheduled',started_at:1792342800,last_active:1792342860,ended_at:1792342860}]
const blueprint = { key:'synthetic-recipe', title:'Synthetic Recipe', description:'Synthetic field-schema example; not a backend recipe.', fields:[
 {name:'time',type:'time',label:'Time',default:'09:00',options:[],optional:false,strict:true,help:'UTC schedule time'},
 {name:'cadence',type:'enum',label:'Cadence',default:'daily',options:['daily','weekly'],optional:false,strict:true,help:''},
 {name:'weekdays',type:'weekdays',label:'Weekdays',default:'1-5',options:['1-5','0,6','*'],optional:false,strict:true,help:''},
 {name:'topic',type:'text',label:'Topic',default:'Status report',options:[],optional:false,strict:true,help:'Synthetic topic'},
 {name:'deliver',type:'enum',label:'Deliver to',default:'origin',options:['origin','local'],optional:false,strict:true,help:''}
] }
const calls:any[] = []
const opened:any[] = []
const boundary = {
 state, theme, fixedClock:'2026-10-19T09:00:00Z', calls, opened,
 snapshot:()=>({jobs:structuredClone(jobs),calls:structuredClone(calls),opened:structuredClone(opened)}),
 api:async (request:any) => {
  calls.push(structuredClone(request))
  const url = new URL(request.path, location.origin)
  const p = url.pathname
  const method = request.method ?? 'GET'
  if (p === '/api/cron/jobs' && method === 'GET') { if (state === 'load-error') throw new Error('Synthetic fixture: cron list unavailable'); return structuredClone(jobs) }
  if (p === '/api/cron/blueprints') return {blueprints:state === 'empty' || state === 'load-error' ? [] : [blueprint]}
  if (p === '/api/cron/delivery-targets') return {targets:[{id:'local',name:'This desktop',home_target_set:true,home_env_var:null},{id:'telegram',name:'Telegram',home_target_set:true,home_env_var:null},{id:'slack',name:'Slack',home_target_set:false,home_env_var:null}]}
  if (p === '/api/model/options') return {providers:[{slug:'synthetic',name:'Synthetic fixture provider',authenticated:true,models:['synthetic-model']}],provider:'synthetic',model:'synthetic-model'}
  if (p === '/api/cron/blueprints/instantiate') {
   const job = {...base,id:'synthetic-blueprint-created',name:'Synthetic Recipe result',prompt:`Synthetic topic: ${request.body.values.topic}`,schedule:{expr:'0 9 * * *'},schedule_display:'Every day at 9:00 AM',deliver:request.body.values.deliver}
   jobs.push(job); return structuredClone(job)
  }
  if (p === '/api/cron/jobs' && method === 'POST') {
   const job = {...base,...request.body,id:'synthetic-created',schedule:{expr:request.body.schedule},schedule_display:request.body.schedule}
   jobs.push(job); return structuredClone(job)
  }
  const match = p.match(/^\/api\/cron\/jobs\/([^/]+)(?:\/(runs|pause|resume|trigger))?$/)
  if (match) {
   const id=decodeURIComponent(match[1]); const action=match[2]; const index=jobs.findIndex(j=>j.id===id)
   if (action === 'runs') return {runs:id === 'script-check' ? scriptRuns : id === 'report-daily' ? runs : []}
   if (index < 0) throw new Error('Synthetic fixture: job not found')
   if (action === 'trigger') return new Promise(()=>{}) // held: genuine optimistic queued UI
   if (action === 'pause' || action === 'resume') jobs[index]={...jobs[index],enabled:action==='resume',state:action==='pause'?'paused':'scheduled'}
   if (method === 'PUT') { const u=request.body.updates; jobs[index]={...jobs[index],...u,schedule:u.schedule?{expr:u.schedule}:jobs[index].schedule,schedule_display:u.schedule ?? jobs[index].schedule_display} }
   if (method === 'DELETE') {jobs.splice(index,1); return {ok:true}}
   return structuredClone(jobs[index])
  }
  throw new Error(`Unexpected synthetic-boundary request: ${method} ${p}`)
 }
}
;(window as any).__cronFixture=boundary
;(window as any).hermesDesktop={api:boundary.api,localSkin:{profile:'default',name:'mono'}}

// Imports are real source modules. Install the boundary before evaluating them.
const [{CronView},{SidebarCronJobsSection},{ThemeProvider},{I18nProvider},{ $cronJobs, setCronFocusJobId },{triggerCronJob},{TooltipProvider},{NotificationStack},{ConfirmHost}] = await Promise.all([
 import('./src/app/cron'),import('./src/app/chat/sidebar/cron-jobs-section'),import('./src/themes'),import('./src/i18n'),import('./src/store/cron'),import('./src/hermes'),import('./src/components/ui/tooltip'),import('./src/components/notifications'),import('./src/components/confirm-host')
])
$cronJobs.set([])
const qc=new QueryClient({defaultOptions:{queries:{retry:false,refetchOnWindowFocus:false}}})
function Fixture() {
 const currentJobs=useStore($cronJobs)
 const [manager,setManager]=useState(true)
 const [open,setOpen]=useState(true)
 const openRun=(id:string,row:any)=>{opened.push({id,row:structuredClone(row)})}
 return <>
  <header data-fixture-label className="fixed inset-x-0 top-0 z-[200] flex h-8 items-center justify-between bg-background px-4 text-xs text-muted-foreground">
   <span>SYNTHETIC FIXTURE — no live backend</span><span>mono · {theme} · en-US · UTC · 2026-10-19 09:00</span>
  </header>
  <aside data-fixture-sidebar className="fixed bottom-8 left-0 top-8 w-80 bg-sidebar px-3 py-4 text-sidebar-foreground">
   <SidebarCronJobsSection jobs={currentJobs} label="Scheduled jobs" onOpenRun={openRun} onManageJob={(id:string)=>{setCronFocusJobId(id);setManager(true)}} onTriggerJob={async(id:string)=>{await triggerCronJob(id)}} onToggle={()=>setOpen(!open)} open={open}/>
   <button data-fixture-open-manager className="mt-6 text-xs text-muted-foreground" onClick={()=>setManager(true)}>Fixture: open real CronView</button>
  </aside>
  {manager && <CronView onClose={()=>setManager(false)} onOpenSession={openRun}/>}
  <NotificationStack/><ConfirmHost/>
  <footer className="fixed inset-x-0 bottom-0 z-[200] h-7 bg-background px-4 py-1 text-xs text-muted-foreground">Actual unchanged CronView + SidebarCronJobsSection · fixture framing only, not full Desktop shell</footer>
 </>
}
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={qc}><I18nProvider initialLocale="en" configClient={null}><ThemeProvider><TooltipProvider><Fixture/></TooltipProvider></ThemeProvider></I18nProvider></QueryClientProvider>)
