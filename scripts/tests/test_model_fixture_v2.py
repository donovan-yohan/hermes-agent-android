"""Future contract unit evidence is synthetic test data, not capture proof."""
import copy
import unittest
from typing import Any
import test_visual_parity_contract as legacy

contract = legacy.contract
FIXTURE = 'bot-model-config-synthetic-v2'


class ModelFixtureV2Test(unittest.TestCase):
    def receipt(self, state='bot-model-saved', platform='desktop'):
        spec = contract.request(contract.load_catalog(), 'bot-model-config', state, 'dark', fixture_id=FIXTURE)
        mapping = spec['state_spec']['platforms'][platform]
        r = (legacy.VisualParityContractTest().desktop_receipt() if platform == 'desktop'
             else legacy.VisualParityContractTest().android_receipt())
        r.update(surface='bot-model-config', state=state, theme='dark', fixture_id=FIXTURE,
                 desktop_upstream_sha=spec['desktop_sha'], capture_mapping=copy.deepcopy(mapping),
                 capture_inputs={**spec['capture_inputs'], 'theme':'dark'},
                 synthetic_inputs=copy.deepcopy(spec['synthetic_inputs']),
                 screenshot_sha256='d'*64, fixture_implementation_sha256='e'*64)
        saved = state == 'bot-model-saved'
        model = 'synthetic-planner-v2' if saved else 'synthetic-planner-v1'
        calls = []
        if state in ('bot-model-confirmation','bot-model-saved','bot-model-save-refused'):
            calls.append({'sequence':2, 'confirmed':False, 'profile':'synthetic-planner',
                          'patch':{'provider':'synthetic-provider','model':'synthetic-planner-v2'},
                          'outcome':'refused' if state.endswith('save-refused') else 'warning',
                          'response':None if state.endswith('save-refused') else
                          {'ok':True,'confirm_required':True,'confirm_message':'Synthetic model cost and data policy require confirmation.','applied':{'model':False}}})
        if saved:
            calls.append({**calls[0], 'sequence':3, 'confirmed':True, 'outcome':'applied',
                          'response':{'ok':True,'applied':{'model':True}}})
        p: dict[str, Any] = dict(source='runtime-capture-worker', selector=mapping['selector'],
                 locator=mapping['locator'], presentation=mapping['presentation'],
                 interaction_semantics=mapping['interaction_semantics'], action_origin=mapping['action_origin'],
                 ordered_actions=mapping['ordered_actions'], events=mapping['required_events'],
                 nodes=[{'text':label} for label in mapping['required_labels']],
                 locator_matches=1, model_calls=calls, authoritative_model=model,
                 fields={'provider':'synthetic-provider','model':model},
                 describe_reads=[{'sequence':1,'profile':'synthetic-planner','model':'synthetic-planner-v1'}],
                 inventory={'method':'model.options','scope':spec['transport_mapping'][platform],
                            'outcome': 'pending' if state.endswith('inventory-loading') else
                            'refused' if state.endswith('inventory-error') else 'loaded'})
        if (state in ('bot-model-confirmation','bot-model-save-refused')
                or (platform == 'desktop' and state == 'bot-model-inventory-loading')):
            p['fields']=None  # No claim that hidden editor fields are visible.
        if saved:
            p['describe_reads'].append({'sequence':5,'profile':'synthetic-planner','model':model})
            p['reopen_sequence']=4
        if state.endswith('inventory-error') or state.endswith('manual'):
            p['manual_fields_visible']=True
        p['action_evidence']=[{'action': action, 'origin': mapping['action_origin'],
                              'sequence': index + 1, 'nodes': [{'text': action}]}
                             for index, action in enumerate(mapping['ordered_actions'])]
        r['state_proof']=copy.deepcopy(p)
        if platform == 'android':
            r['application']['component']=spec['android_activity']
            r['interactions']=spec['state_spec']['interaction']
            label=spec['state_spec']['post_interaction_accessibility']
            r['accessibility']={'expected_description':label,'nodes':[{'text':label}]}
            r['ordered_action_evidence']=[{'action':action,'accessibility':{'nodes':[{'text':action.split(':',1)[1]}]}}
                                          for action in r['interactions']]
            for step in r['ordered_action_evidence']:
                if step['action'].startswith('tap:'):
                    node={'text':step['action'].split(':',1)[1], 'enabled':True,
                          'clickable':True,'package':r['application']['package_name']}
                    step['pre_tap']={'target':node,'clickable_ancestor':node,'ancestor_path':[node]}
            if state.endswith('inventory-loading'):
                r['screenshot_bracket']={'before':r['accessibility'],'after':r['accessibility'],
                    'timing':{'basis':'monotonic-before-fixture-launch','deadline_seconds':20,
                              'screenshot_start_seconds':0.1,'screenshot_end_seconds':0.15,'postcheck_seconds':0.2}}
        if state.endswith('inventory-loading'):
            r['state_proof']['loading_bracket']={
                'screenshot_sha256':r['screenshot_sha256'], 'request_id':'synthetic-options-1',
                'basis':'monotonic-since-interception',
                'before':{'request_id':'synthetic-options-1','pending':True,'response':None,'error':None,'elapsed_ms':100},
                'after':{'request_id':'synthetic-options-1','pending':True,'response':None,'error':None,'elapsed_ms':200}}
        return r

    def test_future_fixture_explicit_and_legacy_unchanged(self):
        catalog=contract.load_catalog()
        old=contract.request(catalog,'bot-model-config','bot-model-saved','dark')
        self.assertEqual('bot-model-config-synthetic-v1',old['fixture_id'])
        self.assertNotIn('platforms',old['state_spec'])
        self.assertEqual('saved-reopened',self.receipt()['capture_mapping']['presentation'])
        self.assertEqual('inline-staged',self.receipt(platform='android')['capture_mapping']['presentation'])

    def test_valid_states(self):
        for state in ('loaded','inventory-loading','inventory-error','manual','confirmation','saved'):
            with self.subTest(state=state):
                contract.validate_receipt(self.receipt('bot-model-'+state),'desktop')
        for state in ('loaded','inventory-loading','inventory-error','manual','confirmation','saved','save-refused'):
            with self.subTest(android_state=state):
                contract.validate_receipt(self.receipt('bot-model-'+state, platform='android'),'android')

    def test_required_mapping_proof_and_identities(self):
        r=self.receipt()
        for key in ('capture_mapping','state_proof','capture_inputs','synthetic_inputs',
                    'screenshot_sha256','fixture_implementation_sha256'):
            bad=copy.deepcopy(r); del bad[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                contract.validate_receipt(bad,'desktop')
        for key in ('selector','locator','presentation','interaction_semantics','action_origin',
                    'ordered_actions','events','nodes','model_calls','describe_reads','fields','inventory'):
            bad=copy.deepcopy(r); bad['state_proof'][key]=[]
            with self.subTest(key=key), self.assertRaises(ValueError):
                contract.validate_receipt(bad,'desktop')

    def test_saved_requires_confirmed_model_only_write_and_named_readback_after_reopen(self):
        r=self.receipt()
        mutations=[lambda p:p['model_calls'][1].update(confirmed=False),
                   lambda p:p['model_calls'][1]['patch'].update(title='Changed title'),
                   lambda p:p['model_calls'][1]['response']['applied'].update(model=False),
                   lambda p:p['describe_reads'][-1].update(sequence=2),
                   lambda p:p['describe_reads'][-1].update(profile='another-profile'),
                   lambda p:p['describe_reads'][-1].update(model='synthetic-planner-v1'),
                   lambda p:p.update(authoritative_model='synthetic-planner-v1'),
                   lambda p:p.update(reopen_sequence=1),
                   lambda p:p.update(locator_matches=2),
                   lambda p:p.update(action_origin='fixture-staged-production-actions')]
        for mutate in mutations:
            bad=copy.deepcopy(r); mutate(bad['state_proof'])
            with self.assertRaises(ValueError): contract.validate_receipt(bad,'desktop')

    def test_read_only_states_cannot_claim_writes_or_hide_inventory_failure(self):
        for state in ('loaded','inventory-error','manual'):
            r=self.receipt('bot-model-'+state)
            r['state_proof']['model_calls']=self.receipt()['state_proof']['model_calls']
            with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')
        r=self.receipt('bot-model-inventory-error'); r['state_proof']['inventory']['outcome']='loaded'
        with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')
        r=self.receipt('bot-model-inventory-error'); r['state_proof']['manual_fields_visible']=False
        with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')

    def test_desktop_loading_hides_fields_but_requires_original_rpc_state(self):
        r=self.receipt('bot-model-inventory-loading')
        self.assertIsNone(r['state_proof']['fields'])
        contract.validate_receipt(r,'desktop')
        mutations=[lambda p:p.pop('fields'),
                   lambda p:p.update(fields={'provider':'synthetic-provider','model':'synthetic-planner-v1'}),
                   lambda p:p.pop('authoritative_model'),
                   lambda p:p.update(authoritative_model='synthetic-planner-v2'),
                   lambda p:p.pop('describe_reads'),
                   lambda p:p.update(describe_reads=[]),
                   lambda p:p['describe_reads'][0].update(profile='another-profile'),
                   lambda p:p['describe_reads'][0].update(model='synthetic-planner-v2'),
                   lambda p:p.update(model_calls=self.receipt()['state_proof']['model_calls']),
                   lambda p:p['inventory'].update(outcome='loaded'),
                   lambda p:p.pop('loading_bracket')]
        for index,mutate in enumerate(mutations):
            bad=copy.deepcopy(r); mutate(bad['state_proof'])
            with self.subTest(mutation=index),self.assertRaises(ValueError):
                contract.validate_receipt(bad,'desktop')

    def test_loading_exception_does_not_hide_other_editor_fields(self):
        for platform in ('desktop','android'):
            states=['loaded','inventory-error','manual','saved']
            if platform == 'android': states.append('inventory-loading')
            for state in states:
                r=self.receipt('bot-model-'+state,platform=platform)
                contract.validate_receipt(r,platform)
                for missing in (False,True):
                    bad=copy.deepcopy(r)
                    if missing: del bad['state_proof']['fields']
                    else: bad['state_proof']['fields']=None
                    with self.subTest(platform=platform,state=state,missing=missing),self.assertRaisesRegex(ValueError,'visible fields'):
                        contract.validate_receipt(bad,platform)

    def test_loading_proof_is_per_png_pending_and_strictly_before_deadline(self):
        r=self.receipt('bot-model-inventory-loading')
        contract.validate_receipt(r,'desktop')
        for side in ('before','after'):
            for key in ('pending','elapsed_ms','response','error','request_id'):
                bad=copy.deepcopy(r); del bad['state_proof']['loading_bracket'][side][key]
                with self.subTest(missing=key,side=side),self.assertRaises(ValueError):
                    contract.validate_receipt(bad,'desktop')
        for key,value in [('pending',False),('elapsed_ms',20000),('elapsed_ms',float('nan')),
                          ('request_id','another'),('response',{}),('error','timeout')]:
            for side in ('before','after'):
                bad=copy.deepcopy(r); bad['state_proof']['loading_bracket'][side][key]=value
                with self.subTest(key=key,side=side),self.assertRaises(ValueError):
                    contract.validate_receipt(bad,'desktop')
        r['state_proof']['loading_bracket']['screenshot_sha256']='f'*64
        with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')

    def test_initial_refusal_requires_discovered_subartifacts_not_confirmed_retry(self):
        r=self.receipt('bot-model-save-refused')
        with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')
        r['state_proof']['discovery']={
            'phase':'initial-write-refused','editor_closed':True,
            'artifacts':[
                {'kind':'initial-refusal-notice','locator':{'kind':'role','role':'alert','name':'Observed refusal'},
                 'locator_matches':1,'screenshot_sha256':'d'*64,'nodes':[{'text':'Observed refusal'}]},
                {'kind':'refused-reopened','locator':{'kind':'role','role':'dialog','name':'Edit profile','exact':True},
                 'locator_matches':1,'screenshot_sha256':'f'*64,'nodes':[{'text':'Edit profile'}],
                 'fields':{'provider':'synthetic-provider','model':'synthetic-planner-v1'},
                 'reopen_sequence':3,'describe_read':{'sequence':4,'profile':'synthetic-planner','model':'synthetic-planner-v1'}}]}
        r['state_proof']['nodes']=[{'text':'Observed refusal'}]
        contract.validate_receipt(r,'desktop')
        for key,value in [('phase','confirmed-retry-refused'),('artifacts',[]),('editor_closed','unknown')]:
            bad=copy.deepcopy(r); bad['state_proof']['discovery'][key]=value
            with self.assertRaises(ValueError): contract.validate_receipt(bad,'desktop')
        for key in ('fields','reopen_sequence','describe_read'):
            bad=copy.deepcopy(r); del bad['state_proof']['discovery']['artifacts'][1][key]
            with self.assertRaises(ValueError): contract.validate_receipt(bad,'desktop')
        r['state_proof']['model_calls'][0]['confirmed']=True
        with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')

    def test_action_evidence_cannot_be_only_a_declared_plan(self):
        for evidence in (None, [], [{'action':'editor:save','origin':'fixture-staged-production-actions','sequence':1,'nodes':[]} ]):
            r=self.receipt(); r['state_proof']['action_evidence']=evidence
            with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')

    def test_platform_request_resolves_explicit_mapping(self):
        spec=contract.request(contract.load_catalog(),'bot-model-config','bot-model-confirmation','light',
                              fixture_id=FIXTURE,platform='desktop')
        self.assertEqual('shared-confirm-dialog',spec['platform_spec']['capture_boundary'])
        self.assertEqual('Switch to synthetic-planner-v2?',spec['platform_spec']['locator']['name'])

    def test_incomplete_declared_mapping_fails_closed(self):
        for key in ('locator','capture_boundary','action_origin','ordered_actions','assertions'):
            catalog=contract.load_catalog()
            del catalog['surfaces']['bot-model-config']['fixture_versions'][FIXTURE]['states']['bot-model-saved']['platforms']['desktop'][key]
            with self.subTest(key=key),self.assertRaises(ValueError):
                contract.request(catalog,'bot-model-config','bot-model-saved','dark',fixture_id=FIXTURE)

    def test_unknown_fixture_missing_platform_and_override_rejected(self):
        with self.assertRaises(ValueError):
            contract.request(contract.load_catalog(),'bot-model-config','bot-model-saved','dark',fixture_id='invented')
        catalog=contract.load_catalog()
        del catalog['surfaces']['bot-model-config']['fixture_versions'][FIXTURE]['states']['bot-model-saved']['platforms']['android']
        with self.assertRaises(ValueError):
            contract.request(catalog,'bot-model-config','bot-model-saved','dark',fixture_id=FIXTURE)
        r=self.receipt(); r['desktop_selector']='body'
        with self.assertRaises(ValueError): contract.validate_receipt(r,'desktop')
