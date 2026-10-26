"""Exercise the repository nginx configuration in disposable Docker containers.

No host ports are published. BFF stubs model the configured X-Real-IP -> XFF mapping;
this checks nginx behavior, not Spring login/session or BFF application startup.
"""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


def docker(*args, check=True):
    return subprocess.run(['docker', *args], capture_output=True, text=True, check=check)


def main():
    root = Path(__file__).resolve().parents[2]
    name = 'yas-xff-test-' + uuid.uuid4().hex[:12]
    network = name + '-net'
    image = 'nginx:1.27.2'  # Match docker-compose.yml.
    hosts = {'storefront': '127.0.0.2', 'backoffice': '127.0.0.3'}
    for host in ('product', 'media', 'customer', 'cart', 'rating', 'order', 'location',
                 'inventory', 'tax', 'promotion', 'payment', 'sampledata', 'swagger-ui', 'identity'):
        hosts[host] = '127.0.0.4'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix='yas-xff-') as temp:
        path = Path(temp)
        shutil.copyfile(root / 'nginx/configuration/custom_proxy_settings.conf', path / 'logging.conf')
        shutil.copyfile(root / 'nginx/templates/default.conf.template', path / 'routes.conf')
        stubs = []
        for ip in ('127.0.0.2', '127.0.0.3'):
            stubs.append('''server {
              listen %s:80;
              access_log off;
              location /api/ {
                rewrite ^/api/(.*)$ /$1 break;
                proxy_bind %s;
                proxy_pass http://127.0.0.1;
                proxy_set_header Host api.yas.local;
                proxy_set_header X-Forwarded-For $http_x_real_ip;
              }
            }''' % (ip, ip))
        stubs.append('''server {
          listen 127.0.0.4:80;
          access_log off;
          location / {
            default_type application/json;
            return 200 '{"xff":"$http_x_forwarded_for","xreal":"$http_x_real_ip","forwarded":"$http_forwarded"}';
          }
        }''')
        (path / 'stubs.conf').write_text('\n'.join(stubs))
        (path / 'nginx.conf').write_text('''events {}
          http {
            include /xff/logging.conf;
            include /xff/routes.conf;
            include /xff/stubs.conf;
          }
        ''')
        try:
            docker('network', 'create', '--internal', network)
            args = ['run', '--detach', '--rm', '--name', name, '--network', network,
                    '--volume', str(path) + ':/xff:ro']
            for host, ip in hosts.items():
                args.extend(['--add-host', host + ':' + ip])
            docker(*args, image, 'nginx', '-c', '/xff/nginx.conf', '-g', 'daemon off;')
            inspection = json.loads(docker('inspect', name).stdout)[0]
            address = inspection['NetworkSettings']['Networks'][network]['IPAddress']

            def request(host, uri, extra=None):
                req = urllib.request.Request('http://' + address + uri,
                    headers={'Host': host, **(extra or {})})
                with opener.open(req, timeout=5) as response:
                    assert response.status == 200
                    return json.load(response)

            for _ in range(30):
                try:
                    baseline = request('api.yas.local', '/product/probe')
                    break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(0.2)
            else:
                raise AssertionError('Test nginx did not become ready')
            docker('exec', name, 'nginx', '-t', '-c', '/xff/nginx.conf')
            client = baseline['xff']
            spoof = {'X-Forwarded-For': '203.0.113.99', 'X-Real-IP': '203.0.113.98',
                     'Forwarded': 'for=203.0.113.97;proto=https'}
            cases = [('direct_api', 'api.yas.local', '/product/probe'),
                     ('storefront_bff', 'storefront', '/api/product/probe'),
                     ('backoffice_bff', 'backoffice', '/api/product/probe'),
                     ('identity', 'identity', '/realms/probe')]
            for label, host, uri in cases:
                response = request(host, uri, spoof)
                assert response == {'xff': client, 'xreal': client, 'forwarded': ''}, label
                print(json.dumps({'test': label, 'passed': True}), flush=True)
            # Arbitrary quoted input must remain valid JSON and must not become the effective IP.
            hostile = '203.0.113.99, "injected": "value\\"'
            assert request('api.yas.local', '/product/json-escape', {'X-Forwarded-For': hostile})['xff'] == client
            time.sleep(0.2)
            logs = docker('logs', name)
            rows = []
            for line in logs.stdout.splitlines():
                if line.startswith('{'):
                    rows.append(json.loads(line))
            assert len(rows) == 8, 'Expected one log per actual gateway hop'
            assert all(r['client_ip'] == r['x_forwarded_for'] == client for r in rows)
            assert any(r['peer_ip'] == '127.0.0.2' and r['client_ip'] != r['peer_ip'] for r in rows)
            assert any(r['peer_ip'] == '127.0.0.3' and r['client_ip'] != r['peer_ip'] for r in rows)
            assert any(r['received_x_forwarded_for'] == hostile for r in rows)
            assert all('?' not in r['request_path'] for r in rows)
            print(json.dumps({'test': 'json_logs_and_bff_peer_separation', 'passed': True,
                              'gateway_events': len(rows)}), flush=True)
        finally:
            docker('rm', '--force', name, check=False)
            docker('network', 'rm', network, check=False)


if __name__ == '__main__':
    main()
