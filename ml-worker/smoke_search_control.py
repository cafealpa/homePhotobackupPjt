"""임시 설치 폴더와 실제 JAR/Python으로 설정 화면의 검색 시작/중지 계약 검증."""
import argparse
import json
from pathlib import Path
import shutil
import site
import subprocess
import sys
import tempfile
import time

import requests
from smoke_search import free_port


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--browser-seconds', type=int, default=0)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix='homephoto-control-') as tmp:
        root = Path(tmp)
        worker = root / 'ml-worker'
        worker.mkdir()
        for name in ('search_service.py', 'face_search.py'):
            shutil.copy2(source / name, worker / name)
        shutil.copytree(source / 'search-model', worker / 'search-model')
        subprocess.run([sys.executable, '-m', 'venv', '--without-pip', str(worker / '.venv-search')], check=True)
        # Keep tests isolated without downloading the already tested dependencies again.
        packages = worker / '.venv-search' / 'Lib' / 'site-packages'
        packages.mkdir(parents=True, exist_ok=True)
        (packages / 'test-dependencies.pth').write_text('\n'.join(site.getsitepackages()), encoding='utf-8')
        base = f'http://127.0.0.1:{free_port()}'
        search_port = free_port()
        token = 'search-control-test-token-0123456789'
        headers = {'X-Api-Key': 'control-test-key', 'X-HomePhoto-Action': 'search-service'}
        session = requests.Session()
        session.trust_env = False
        log = (root / 'server.log').open('w', encoding='utf-8')
        server = subprocess.Popen([args.java, '-jar', str(Path(args.jar).resolve()),
            '--server.address=127.0.0.1', '--server.port=' + base.rsplit(':', 1)[1],
            '--homephoto.storage-root=' + str(root / 'photos'), '--homephoto.api-key=control-test-key',
            '--homephoto.search.enabled=true', '--homephoto.search.token=' + token,
            '--homephoto.search.base-url=http://127.0.0.1:' + str(search_port),
            '--homephoto.search.worker-dir=' + str(worker)], cwd=root, stdout=log, stderr=log)
        endpoint = base + '/api/v1/admin/search-service'
        try:
            for _ in range(120):
                try:
                    if session.get(base + '/api/v1/health', timeout=1).ok:
                        break
                except requests.RequestException:
                    pass
                time.sleep(.5)
            else:
                raise AssertionError('Server did not start')
            assert session.post(endpoint + '/start', timeout=5).status_code == 401
            assert session.get(endpoint, headers=headers, timeout=5).json()['state'] == 'stopped'
            assert session.post(endpoint + '/start', headers=headers, timeout=5).json()['state'] == 'starting'
            pid = (worker / 'search-data' / 'service.pid').read_text()
            session.post(endpoint + '/start', headers=headers, timeout=5).raise_for_status()
            assert (worker / 'search-data' / 'service.pid').read_text() == pid
            for _ in range(120):
                state = session.get(endpoint, headers=headers, timeout=5).json()
                if state['state'] == 'running':
                    break
                assert state['state'] == 'starting', state
                time.sleep(.5)
            else:
                raise AssertionError('Model did not become ready')
            assert state['indexedPhotos'] == 0 and state['indexedFaces'] == 0 and state['canStop']
            print(json.dumps({'browser_url': base, 'state': state, 'duplicate_start': 'same PID'}), flush=True)
            if args.browser_seconds:
                time.sleep(args.browser_seconds)
            state = session.post(endpoint + '/stop', headers=headers, timeout=15).json()
            assert state['state'] == 'stopped' and state['canStart'], state
            assert not (worker / 'search-data' / 'service.pid').exists()
            print('PASS: real JAR start/readiness/counts/duplicate start/stop; isolated Python ' + sys.version.split()[0], flush=True)
        finally:
            try:
                session.post(endpoint + '/stop', headers=headers, timeout=15)
            except requests.RequestException:
                pass
            server.terminate()
            server.wait(timeout=20)
            log.close()


if __name__ == '__main__':
    main()
