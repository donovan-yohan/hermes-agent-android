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
        assert re.search(r'displayId=0.*PR344_SYNTHETIC_FOCUS_DENIAL', owner)
        for i in (failure, dismiss):
            assert 'lifecycle=RESUMED destroyed=false attached=true focus=false' in lines[i]
            match = re.search(r'frames=(\d+)', lines[i])
            assert match is not None and int(match[1]) > 0
        return True
    except (AssertionError, KeyError, StopIteration, TypeError, ValueError):
        return False
