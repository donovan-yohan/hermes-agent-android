#!/usr/bin/env python3
"""Structural backstop for ADR 0005; runtime policy is covered by JVM tests."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / 'app/src/main'


def check(xml, manifest, app, clients):
    problems = []
    config = ET.fromstring(xml)
    if len(config) != 1 or config[0].tag != 'base-config' or config[0].get('cleartextTrafficPermitted') != 'true':
        problems.append('Network config must contain exactly the HTTP-enabled base-config (ADR 0005).')
    if 'android:networkSecurityConfig="@xml/network_security_config"' not in manifest or 'usesCleartextTraffic="true"' in manifest:
        problems.append('Manifest must use the network config, not usesCleartextTraffic=true.')
    for gate in ('.followRedirects(false)', '.followSslRedirects(false)', '.proxy(java.net.Proxy.NO_PROXY)'):
        if gate not in app:
            problems.append('Shared Gateway HTTP client lost its auth safeguard: ' + gate)
    expected = {'GatewayTransportPolicy.kt': 1}
    # Derived newBuilder() clients retain the auth safeguards; forbid adding
    # independent clients elsewhere (including constructor defaults).
    if clients != expected:
        problems.append('Independent OkHttp client inventory changed; route production through the shared client.')
    return problems


def main():
    app = (MAIN / 'kotlin/com/hermesagent/mobile/data/gateway/GatewayTransportPolicy.kt').read_text()
    manifest = (MAIN / 'AndroidManifest.xml').read_text()
    xml = (MAIN / 'res/xml/network_security_config.xml').read_text()
    clients = {}
    for path in (MAIN / 'kotlin').rglob('*.kt'):
        count = len(re.findall(r'OkHttpClient\s*(?:\(\s*\)|\.Builder\s*\()', path.read_text()))
        if count:
            clients[path.name] = clients.get(path.name, 0) + count
    problems = check(xml, manifest, app, clients)
    if '--self-test' in sys.argv:
        assert not problems, problems
        for gate in ('.followRedirects(false)', '.followSslRedirects(false)', '.proxy(java.net.Proxy.NO_PROXY)'):
            assert check(xml, manifest, app.replace(gate, ''), clients)
        assert check(xml, manifest, app, {**clients, 'Unsafe.kt': 1})
        assert check('<network-security-config><base-config cleartextTrafficPermitted="false" /></network-security-config>', manifest, app, clients)
        print('ok    Gateway transport structural self-tests')
    for problem in problems:
        print('FAIL  ' + problem)
    if not problems:
        print('ok    HTTP(S) Gateway auth safeguards and client inventory')
    return bool(problems)


if __name__ == '__main__':
    sys.exit(main())
