#!/usr/bin/env python3
"""Bounded numeric host-only samples; no process args, paths, network, or user data."""
import json
import os
from pathlib import Path
import re
import sys
import time
from systemui_anr import allowed


def sample():
    result = {'epoch': time.time(), 'vcpus': os.cpu_count(), 'load': os.getloadavg()}
    result['memory_kb'] = dict(re.findall(r'^(MemTotal|MemAvailable|SwapTotal|SwapFree):\s+(\d+)', Path('/proc/meminfo').read_text(), re.M))
    result['cpu_ticks'] = Path('/proc/stat').read_text().splitlines()[0].split()[1:]
    result['vmstat'] = dict(re.findall(r'^(pswpin|pswpout|oom_kill|pgmajfault) (\d+)', Path('/proc/vmstat').read_text(), re.M))
    result['pressure'] = {name: Path('/proc/pressure', name).read_text().strip() for name in ('cpu', 'memory', 'io')}
    return result


if __name__ == '__main__':
    if not allowed():
        raise SystemExit('synthetic host binding rejected')
    out = Path(os.environ['RUNNER_TEMP']) / 'systemui-host.jsonl'
    with out.open('x') as f:
        for _ in range(450):
            f.write(json.dumps(sample()) + '\n')
            f.flush()
            time.sleep(2)
