#!/usr/bin/env python3
"""Submit CPU/GPU jobs to S25 Compute through TailSocks Serve."""
import argparse
import json
import hashlib
import os
import time
import urllib.request
import urllib.error
import urllib.parse

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('url', help='TailSocks Serve HTTPS URL')
p.add_argument('task', choices=['capabilities', 'sha256', 'gpu-vector', 'cancel'])
p.add_argument('--job', help='Job ID for cancel')
p.add_argument('--token', default=os.environ.get('S25_COMPUTE_TOKEN'))
p.add_argument('--values', help='Comma separated signed integers for GPU')
p.add_argument('--text', help='Text to hash on the phone CPU')
a = p.parse_args()
if not a.token:
    p.error('set S25_COMPUTE_TOKEN or pass --token')

def call(method, path):
    req = urllib.request.Request(a.url.rstrip('/') + path, method=method,
        headers={'Authorization': 'Bearer ' + a.token})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)

try:
    if a.task == 'capabilities':
        result = call('GET', '/capabilities')
    elif a.task == 'cancel':
        if not a.job:
            p.error('cancel requires --job')
        result = call('DELETE', '/jobs/' + a.job)
    else:
        query = {}
        if a.values is not None: query['values'] = a.values
        if a.text is not None: query['text'] = a.text
        suffix = '?' + urllib.parse.urlencode(query) if query else ''
        result = call('POST', '/jobs/' + a.task + suffix)
        job = result['id']
        print('Job:', job, flush=True)
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            result = call('GET', '/jobs/' + job)
            if result.get('status') not in ('running', 'cancelling'):
                break
            time.sleep(.5)
        else:
            raise TimeoutError('Job is still running; inspect or cancel using ID ' + job)
    print(json.dumps(result, indent=2))
    if a.task in ('sha256', 'gpu-vector'):
        if result.get('status') != 'complete':
            p.exit(1, 'Job did not complete successfully\n')
        if a.task == 'sha256' and a.text is not None:
            expected = hashlib.sha256(a.text.encode('utf-8')).hexdigest()
            if result.get('sha256') != expected:
                p.exit(1, 'CPU result differs from local SHA-256\n')
            print('CPU result matches local SHA-256')
        if a.task == 'gpu-vector':
            inputs = [int(v) for v in a.values.split(',')] if a.values else list(range(1024))
            expected = [(v * 3 + 7) & 0xffffffff for v in inputs]
            if result.get('values') != expected or not result.get('verified'):
                p.exit(1, 'GPU result differs from local reference\n')
            print('GPU result matches local reference')
except (urllib.error.URLError, TimeoutError, KeyError) as exc:
    p.exit(1, str(exc) + '\n')
