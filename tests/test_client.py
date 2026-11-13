import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import subprocess
import threading
import unittest

CLIENT = Path(__file__).resolve().parents[1] / 'tools/compute-client.py'

class ClientTests(unittest.TestCase):
    def run_client(self, task, result, extra):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self): self.respond({'id':'test-job'})
            def do_GET(self): self.respond(result)
            def respond(self, value):
                if self.headers.get('Authorization') != 'Bearer test-token':
                    self.send_error(401); return
                self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers()
                self.wfile.write(json.dumps(value).encode())
        server=HTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            return subprocess.run(['python3',str(CLIENT),f'http://127.0.0.1:{server.server_port}',task,'--token','test-token']+extra,capture_output=True,text=True)
        finally: server.shutdown(); server.server_close(); thread.join()
    def test_cpu_accepts_correct_digest(self):
        result=self.run_client('sha256',{'status':'complete','sha256':hashlib.sha256(b'hello').hexdigest()},['--text','hello'])
        self.assertEqual(result.returncode,0,result.stderr)
    def test_gpu_rejects_incorrect_readback(self):
        result=self.run_client('gpu-vector',{'status':'complete','verified':True,'values':[99]},['--values','1'])
        self.assertEqual(result.returncode,1)
        self.assertIn('differs',result.stderr)
    def test_gpu_accepts_unsigned_overflow(self):
        result=self.run_client('gpu-vector',{'status':'complete','verified':True,'values':[4,2147483652]},['--values=-1,2147483647'])
        self.assertEqual(result.returncode,0,result.stderr)

if __name__=='__main__': unittest.main()
