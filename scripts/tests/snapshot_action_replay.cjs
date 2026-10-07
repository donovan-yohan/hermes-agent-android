#!/usr/bin/env node
'use strict';
/* Independent offline replay of retained installed action modules at
 * a421e43855164a8197daf9d8d40fe71c6996bb0d. No device, SDK command, network,
 * Gradle or connected workload is executed. Compatibility helper IS executed
 * in fresh Python subprocesses against copied workflow and physical fixtures.
 * Usage: node scripts/tests/snapshot_action_replay.cjs [--modules DIR] [--output DIR]
 * On non-Linux only /proc/cpuinfo reads are supplied by a fixture shim; helper
 * compatibility/receipt/config/snapshot logic remains the actual copied code.
 * Native snapshot layout: snapshots/default_boot/{snapshot.pb,ram.bin,textures.bin,hardware.ini}.
 * Their nonempty synthetic bytes prove presence/layout only, not loadability.
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const ROOT = path.resolve(__dirname, '../..');
const SCRATCH = process.env.TMPDIR || path.dirname(ROOT);
function option(name, fallback) { const i = process.argv.indexOf(name); return i < 0 ? fallback : process.argv[i + 1]; }
const MODULES = option('--modules', path.resolve(ROOT, '../snapshot-action'));
const OUT = path.resolve(option('--output', fs.mkdtempSync(path.join(SCRATCH, 'snapshot-action-replay-'))));
const WORKFLOW = '.github/workflows/android-exact-head.yml';
const PIN = 'a421e43855164a8197daf9d8d40fe71c6996bb0d';
const FILES = ['main', 'emulator-manager', 'sdk-installer', 'input-validator', 'script-parser', 'channel-id-mapper'];
const sources = Object.fromEntries(FILES.map(n => [n, fs.readFileSync(path.join(MODULES, `lib_${n}.js`), 'utf8')]));
const provenance = Object.fromEntries(FILES.map(n => [n, crypto.createHash('sha256').update(sources[n]).digest('hex')]));
fs.mkdirSync(OUT, {recursive:true});
function put(p, s) { fs.mkdirSync(path.dirname(p), {recursive:true}); fs.writeFileSync(p, s); }
function blocks(text) {
  return [...text.matchAll(/^      - name: (.+)\n([\s\S]*?)(?=^      - (?:name:|uses:)|^  [a-z]|$(?![\s\S]))/gm)].map(m => ({name:m[1], body:m[2]}));
}
function inputsFor(text, name) {
  const b = blocks(text).find(b => b.name === name); assert(b, `workflow step absent: ${name}`);
  assert(b.body.includes(`reactivecircus/android-emulator-runner@${PIN}`));
  const inputs = {'api-level':'34','system-image-api-level':'','target':'google_apis','arch':'x86_64','profile':'pixel_6','cores':'2','ram-size':'','heap-size':'','disk-size':'','sdcard-path-or-size':'','avd-name':'test','force-avd-creation':'false','emulator-boot-timeout':'600','emulator-port':'5554','disable-animations':'true','disable-spellchecker':'false','disable-linux-hw-accel':'auto','enable-hw-keyboard':'false','emulator-build':'','working-directory':'','ndk':'','cmake':'','channel':'stable'};
  const lines=b.body.split('\n');
  for(let i=0;i<lines.length;i++) {
    const m=lines[i].match(/^          ([a-z-]+): (.*)$/); if(!m) continue;
    if(m[2]==='|') { const acc=[]; while(i+1<lines.length && /^            /.test(lines[i+1])) acc.push(lines[++i].slice(12)); inputs[m[1]]=acc.join('\n'); }
    else inputs[m[1]]=m[2].replace(/^(['"])(.*)\1$/, '$2');
  }
  assert(inputs.script, 'no workflow workload'); return inputs;
}
function fixture(dir) {
  assert(!fs.existsSync(dir), 'recipient fixture must be a fresh directory: '+dir);
  fs.mkdirSync(dir, {recursive:true});
  for(const f of [WORKFLOW,'scripts/ci_avd_cache.py']) put(path.join(dir,f),fs.readFileSync(path.join(ROOT,f)));
  const home=path.join(dir,'home'), sdk=path.join(dir,'sdk');
  const env={...process.env,HOME:home,ANDROID_HOME:sdk,ANDROID_AVD_HOME:path.join(home,'.android/avd'),ImageVersion:'offline-replay-image-1',GITHUB_OUTPUT:path.join(dir,'github-output'),GITHUB_ENV:path.join(dir,'github-env')};
  for(const [f,data] of Object.entries({'emulator/emulator':'synthetic emulator binary','emulator/source.properties':'Pkg.Revision=36.6.11\n','emulator/package.xml':'<localPackage path="emulator"/>','system-images/android-34/google_apis/x86_64/system.img':'synthetic image bytes','system-images/android-34/google_apis/x86_64/source.properties':'Pkg.Revision=12\n','system-images/android-34/google_apis/x86_64/package.xml':'<localPackage path="system-images;android-34;google_apis;x86_64"/>','cmdline-tools/latest/bin/avdmanager':'synthetic profile tool','cmdline-tools/latest/source.properties':'Pkg.Revision=20.0\n'})) put(path.join(sdk,f),data);
  fs.mkdirSync(env.ANDROID_AVD_HOME,{recursive:true});
  return {dir,env,inputs:null,events:[],failures:[],helpers:[]};
}
// Real subprocess/helper; synthetic ps observations and virtual wait match the
// mocked creator lifecycle. Procfs fixture only supplies CPU data on macOS.
const bootstrap = `import runpy,sys,os,subprocess,time\nfrom pathlib import Path\noriginal=Path.read_text\ndef read_text(self,*a,**kw):\n if str(self)=='/proc/cpuinfo' and not self.exists(): return 'vendor_id : offline-fixture\\nmodel name : offline-fixture\\nflags : sse sse2\\n'\n return original(self,*a,**kw)\nPath.read_text=read_text\nreal_run=subprocess.run\nreal_clock=time.monotonic\noffset=0\nscans=0\ndef scan(args,*a,**kw):\n global scans\n if args==['ps','-ww','-eo','uid=,pid=,args=']:\n  scans+=1\n  live=os.environ.get('REPLAY_CREATOR_LIVE')=='1' or (os.environ.get('REPLAY_DELAYED_EXIT')=='1' and scans==1)\n  text=f"{os.getuid()} 123 {os.environ['ANDROID_HOME']}/emulator/qemu/linux-x86_64/qemu-system-x86_64 -avd test -port 5554\\n" if live else ''\n  return subprocess.CompletedProcess(args,0,text)\n return real_run(args,*a,**kw)\ndef wait(seconds):\n global offset\n offset+=31 if os.environ.get('REPLAY_CREATOR_LIVE')=='1' else seconds\nsubprocess.run=scan\ntime.monotonic=lambda:real_clock()+offset\ntime.sleep=wait\nsys.argv=['scripts/ci_avd_cache.py',sys.argv[1]]\nrunpy.run_path('scripts/ci_avd_cache.py',run_name='__main__')\n`;
function helper(state, command) {
  const result=cp.spawnSync(process.env.PYTHON || 'python3',['-c',bootstrap,command],{cwd:state.dir,env:state.env,encoding:'utf8',timeout:150000});
  const log={command,status:result.status,stdout:result.stdout,stderr:result.stderr,error:result.error?.message}; state.helpers.push(log); return log;
}
function shellHelper(state, script) {
  const match=script.match(/^python3 scripts\/ci_avd_cache\.py ([a-z-]+)$/); assert(match,`unsupported compatibility command: ${script}`);
  const r=helper(state,match[1]); if(r.status!==0) throw new Error(`helper ${match[1]} exit ${r.status}: ${r.stderr}`);
}
async function action(state, step, mode) {
  const workflow=fs.readFileSync(path.join(state.dir,WORKFLOW),'utf8');
  const inputs=state.inputs=inputsFor(workflow,step);
  let launched=false; let resolveTerminated;
  const terminated=new Promise(r=>resolveTerminated=r);
  const mocks={
    '@actions/core':{getInput:n=>inputs[n]||'',addPath:()=>{},exportVariable:(n,v)=>state.env[n]=v,setFailed:e=>state.failures.push(String(e?.message||e))},
    '@actions/io':{mkdirP:async p=>fs.mkdirSync(p,{recursive:true}),mv:async()=>{throw Error('unexpected move')},rmRF:async()=>{throw Error('unexpected removal')}},
    '@actions/tool-cache':{downloadTool:async()=>{throw Error('offline: no download')},extractZip:async()=>{throw Error('offline: no extraction')}},
    fs:{constants:fs.constants,existsSync:fs.existsSync,accessSync:p=>{assert.equal(p,'/dev/kvm');}},
    '@actions/exec':{exec:async(command,args=[],options={})=>{
      state.events.push({command,args});
      const avd=path.join(state.env.ANDROID_AVD_HOME,inputs['avd-name']+'.avd');
      if(command.includes('sdkmanager --install emulator') && mode==='sdk-update') fs.appendFileSync(path.join(state.env.ANDROID_HOME,'emulator/emulator'),' changed during action installation');
      if(command.includes('avdmanager create avd')) {
        put(path.join(avd,'config.ini'),'hw.device.name=pixel_6\nhw.ramSize=2048\nhw.keyboard=no\nimage.sysdir.1=system-images/android-34/google_apis/x86_64/\n');
        put(path.join(state.env.ANDROID_AVD_HOME,'test.ini'),`avd.ini.encoding=UTF-8\npath=${avd}\npath.rel=avd/test.avd\n`);
      }
      if(command.includes("printf '") && command.includes('/config.ini')) {
        const m=command.match(/printf '([^']*)'/); assert(m,'unrecognized installed action config write');
        fs.appendFileSync(path.join(avd,'config.ini'),m[1].replace(/\\n/g,'\n'));
      }
      if(command.includes('/emulator/emulator -port')) { launched=true; state.launch=command; state.env.REPLAY_CREATOR_LIVE='1'; }
      if(command.includes('getprop sys.boot_completed')) options.listeners.stdout(Buffer.from('1'));
      if(command==='sh') {
        const script=args[1];
        if(script.includes('ci_avd_cache.py')) shellHelper(state,script);
        else if(script.includes('ci_prebuilt.py connected')) { state.connected=true; if(mode==='connected-failure') throw new Error('original connected failure 17'); }
        // Preparation and focus instrumentation are recorded only, never run.
      }
      if(command.endsWith('emu kill')) {
        assert(launched,'kill without launch');
        if(!inputs['emulator-options'].includes('-no-snapshot-save')) {
          for(const f of ['snapshot.pb','ram.bin','textures.bin','hardware.ini']) put(path.join(avd,'snapshots/default_boot',f),f==='snapshot.pb'?Buffer.from([8,1,26,0,34,0,42,0,80,0,88,0]):'synthetic offline native snapshot artifact '+f);
          state.saved=true;
        }
        if(mode==='kill-failure' || mode==='kill-success-live') {
          resolveTerminated(); // Action completion, NOT creator exit.
          if(mode==='kill-failure') throw Error('synthetic shutdown command failed; process remains alive');
          return 0;
        }
        state.env.REPLAY_CREATOR_LIVE='0';
        if(mode==='delayed-exit') state.env.REPLAY_DELAYED_EXIT='1';
        if(mode==='incomplete-save') fs.rmSync(path.join(avd,'snapshots/default_boot/ram.bin'));
        resolveTerminated();
      }
      return 0;
    }}
  };
  const modules={};
  function load(n) {
    if(modules[n])return modules[n];
    const exports=modules[n]={};
    const context={exports,require:id=>{if(id.startsWith('./'))return load(id.slice(2));assert(id in mocks,`unmocked import ${id}`);return mocks[id];},console:{log:()=>{},warn:()=>{}},process:{platform:'linux',arch:'x64',env:state.env,chdir:()=>{throw Error('unexpected chdir');}},Buffer,Error,setTimeout:()=>{throw Error('unexpected boot retry');}};
    // Evaluate the EXACT retained source, including its original run(); call.
    vm.runInNewContext(sources[n],context,{filename:path.join(MODULES,`lib_${n}.js`)});
    return exports;
  }
  load('main');
  await Promise.race([terminated,new Promise((_,reject)=>{const t=setTimeout(()=>reject(Error('action did not terminate')),10000);t.unref();})]);
  await new Promise(r=>setImmediate(r));
  const creations=state.events.filter(e=>e.command.includes('avdmanager create avd'));
  assert.equal(creations.length,step==='Create the AVD snapshot'?1:0,'miss creates, restored hit reuses');
  assert.equal(state.events.filter(e=>e.command.includes('/emulator/emulator -port')).length,1);
  assert(state.events.some(e=>e.command.includes('sdkmanager --install emulator --channel=0')));
  assert(state.events.some(e=>e.command.includes('system-images;android-34;google_apis;x86_64')));
  return state;
}
function sealFromWorkflow(state) {
  const text=fs.readFileSync(path.join(state.dir,WORKFLOW),'utf8');
  const afterCreator=text.slice(text.indexOf('      - name: Create the AVD snapshot'),text.indexOf('      - name: Run the instrumented lane'));
  const seal=afterCreator.match(/^        run: python3 scripts\/ci_avd_cache\.py seal\s*$/m) || afterCreator.match(/^          python3 scripts\/ci_avd_cache\.py seal\s*$/m);
  assert(seal,'workflow must seal after creator action termination, before cache save');
  assert(state.saved,'seal must follow saved native-layout artifacts');
  shellHelper(state,'python3 scripts/ci_avd_cache.py seal');
  state.events.push({command:'parent workflow seal after action termination'});
}
function worker(spec) {
  return (async()=>{
    const state=fixture(spec.dir);
    const recorded=helper(state,'record'); assert.equal(recorded.status,0,recorded.stderr);
    if(spec.cache) {
      fs.cpSync(spec.cache,state.env.ANDROID_AVD_HOME,{recursive:true});
      // Actions restores at the same absolute HOME on another runner. Offline
      // workers use unique roots, so relocate only that synthetic locator.
      put(path.join(state.env.ANDROID_AVD_HOME,'test.ini'),`avd.ini.encoding=UTF-8\npath=${path.join(state.env.ANDROID_AVD_HOME,'test.avd')}\npath.rel=avd/test.avd\n`);
    }
    if(spec.mode==='divergent-config') fs.appendFileSync(path.join(state.env.ANDROID_AVD_HOME,'test.avd/config.ini'),'hw.ramSize=9999\n');
    if(spec.mode==='missing-snapshot') fs.rmSync(path.join(state.env.ANDROID_AVD_HOME,'test.avd/snapshots/default_boot/snapshot.pb'));
    await action(state,spec.step,spec.mode);
    if(spec.step==='Create the AVD snapshot' && !state.failures.length) {
      try { sealFromWorkflow(state); } catch(e) { state.lifecycleError=e.message; }
    }
    return state;
  })();
}
async function main() {
  if(process.argv.includes('--worker')) {
    const spec=JSON.parse(fs.readFileSync(option('--worker'),'utf8'));
    try { const result=await worker(spec); put(spec.result,JSON.stringify(result,null,2)); }
    catch(e) {put(spec.result,JSON.stringify({error:e.stack},null,2));process.exitCode=1;} return;
  }
  const results=[];const errors=[];
  function run(name,step,mode,cache) {
    const spec={dir:path.join(OUT,name),step,mode,cache,result:path.join(OUT,name+'.json')};
    const specPath=path.join(OUT,name+'-spec.json'); put(specPath,JSON.stringify(spec));
    const r=cp.spawnSync(process.execPath,[__filename,'--modules',MODULES,'--output',OUT,'--worker',specPath],{encoding:'utf8',timeout:180000,env:process.env});
    const result=fs.existsSync(spec.result)?JSON.parse(fs.readFileSync(spec.result)): {error:r.error?.message||r.stderr};
    results.push({name,processStatus:r.status,...result}); return result;
  }
  const creator=run('miss','Create the AVD snapshot','miss');
  try { assert(!creator.error,creator.error); assert.equal(creator.failures.length,0); assert(creator.saved); assert(!creator.lifecycleError,creator.lifecycleError); assert.equal(creator.helpers.filter(h=>h.command==='verify-miss'&&h.status===0).length,2,'both creator hooks must explicitly allow miss'); }catch(e){errors.push('miss lifecycle: '+e.message);}
  const cache=path.join(OUT,'saved-cache');
  for(const mode of ['kill-failure','kill-success-live','delayed-exit','incomplete-save']) {
    const r=run(mode,'Create the AVD snapshot',mode);
    try {
      assert(!r.error,r.error); assert.equal(r.failures.length,0,'upstream swallows shutdown failure');
      const seal=r.helpers.find(h=>h.command==='seal'); assert(seal,'seal must execute independently');
      assert.equal(seal.status,mode==='delayed-exit'?0:1);
      assert.equal(fs.existsSync(path.join(r.env.ANDROID_AVD_HOME,'test.avd/creator-manifest.json')),mode==='delayed-exit');
    }catch(e){errors.push(mode+': '+e.message);}
  }
  if(!creator.error && creator.failures.length===0) {
    fs.cpSync(creator.env.ANDROID_AVD_HOME,cache,{recursive:true});
    for(const mode of ['hit','divergent-config','missing-snapshot','sdk-update','connected-failure']) {
      const r=run(mode,'Run the instrumented lane',mode,cache);
      try {
        assert(!r.error,r.error); assert(!r.saved,'consumer must not save snapshot');
        assert(r.launch.includes('-no-snapshot-save'));
        const guard=mode==='divergent-config'||mode==='missing-snapshot'||mode==='sdk-update';
        if(guard) {assert.equal(r.failures.length,2,'prelaunch swallow plus first workload refusal'); assert(!r.connected,'connected must never be reached'); assert.equal(r.events.filter(e=>e.command==='sh').length,2,'only prelaunch and first workload guard');}
        else if(mode==='connected-failure') {assert(r.connected);assert.equal(r.failures.length,1);assert(r.failures[0].includes('original connected failure 17'));}
        else {assert.equal(r.failures.length,0);assert(r.connected,'successful fresh-recipient hit must reach workload');}
      }catch(e){errors.push(mode+': '+e.message);}
    }
  }
  const report={pin:PIN,moduleHashes:provenance,workflowHash:crypto.createHash('sha256').update(fs.readFileSync(path.join(ROOT,WORKFLOW))).digest('hex'),hostProcfsShim:process.platform!=='linux',limits:['No native emulator execution or proof of binary snapshot loadability.','SDK installation, avdmanager creation/config append, boot, termination/snapshot save and connected workload are mocked; compatibility Python helper and filesystem receipts run for real.','Cache is a physical AVD tree copy into a new child Node process; not GitHub cache service.','Synthetic snapshot files use native default_boot layout; contents are deliberately not protobuf/RAM/GPU data.'],results,errors};
  put(path.join(OUT,'report.json'),JSON.stringify(report,null,2)+'\n');
  console.log(`${errors.length?'RED':'PASS'} actual pinned action replay; report ${path.join(OUT,'report.json')}`);
  for(const e of errors)console.error(e); if(errors.length)process.exitCode=1;
}
main().catch(e=>{console.error(e);process.exitCode=1;});
