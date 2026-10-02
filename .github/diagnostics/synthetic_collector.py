"""Validate deliberate failure separately; never convert it to suite success."""
import re


def verify(result, lines):
    try:
        assert result['raw_exit'] > 0 and not result['timed_out']
        assert result['same_apks'] and result['exact_identity_multiset'] and result['exact_start_finish']
        assert len(result['cases']) == 1
        case = result['cases'][0]
        assert case['id'] == 'SyntheticFocusDenialTest#deliberateDenialCapturesOwnerBeforeTeardown'
        assert not case['skipped'] and len(case['failures']) == 1
        assert 'Activity not awake, unlocked and input-focused within 15000 ms' in case['failures'][0]
        assert result['failure_events'] == ['com.hermesagent.mobile.device.' + case['id']]
        assert not any('CAPTURE_ERROR' in x or 'PROBE_ERROR' in x or 'CURRENT_BLOCK_MISSING' in x for x in lines)
        def single(marker):
            indices = [i for i, x in enumerate(lines) if marker in x]
            assert len(indices) == 1, marker
            return indices[0]
        denial = single('SYNTHETIC DENIAL_ESTABLISHED')
        begin = single('FocusSnapshot: BEGIN test=')
        probe = single('PROBE input ')
        block = single('FocusedWindows:')
        end = single('FocusSnapshot: END test=')
        failure = single('SYNTHETIC ORIGINAL_FAILURE')
        dismiss = single('SYNTHETIC BEFORE_DIALOG_DISMISS')
        destroyed = single('SYNTHETIC LIFECYCLE ON_DESTROY')
        assert denial < begin < probe < block < end < failure < dismiss < destroyed
        # Limit owner match to the current block, before the next PROBE.
        following = next(i for i in range(block + 1, end) if 'PROBE ' in lines[i])
        owner = '\n'.join(lines[block + 1:following])
        # Titles/channel names are descriptive only, never role authority.
        assert re.search(r'displayId=0\b.*name=\S+', owner)
        before = single('WINDOW_IDENTITY phase=before ')
        after = single('WINDOW_IDENTITY phase=after ')
        assert begin < before < probe < block < after < following < end
        snapshots = []
        for index in (before, after):
            fields = dict(re.findall(r'(\w+)=([^\s]+)', lines[index]))
            for key, value in dict(main='true', activityAttached='true', dialogAttached='true',
                                   activityDisplay='0', dialogDisplay='0', activityFocus='false',
                                   dialogFocus='true', activityWindowIdFocus='false',
                                   dialogWindowIdFocus='true', distinctTokens='true',
                                   activityDestroyed='false', dialogShowing='true').items():
                assert fields[key] == value, key
            assert re.fullmatch(r'client-[1-9][0-9]*', fields['activityToken'])
            assert re.fullmatch(r'client-[1-9][0-9]*', fields['dialogToken'])
            assert fields['activityToken'] != fields['dialogToken']
            snapshots.append(fields)
        for key in ('activityToken', 'dialogToken'):
            assert snapshots[0][key] == snapshots[1][key]
        interval = re.search(r'begin=(\d+) end=(\d+)', lines[probe])
        assert interval is not None
        assert int(snapshots[0]['uptime']) <= int(interval[1]) <= int(interval[2]) <= int(snapshots[1]['uptime'])
        for i in (failure, dismiss):
            assert 'lifecycle=RESUMED destroyed=false attached=true focus=false' in lines[i]
            match = re.search(r'frames=(\d+)', lines[i])
            assert match is not None and int(match[1]) > 0
        return True
    except (AssertionError, KeyError, StopIteration, TypeError, ValueError):
        return False
