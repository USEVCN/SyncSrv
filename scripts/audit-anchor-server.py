#!/usr/bin/env python3
"""Independent HTTPS append-only audit-head receiver; deploy on a separate trust domain."""
import contextlib, fcntl, tempfile
import argparse, base64, hashlib, json, os, ssl, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
class Receiver:
    def __init__(self,directory,client,key):
        self.directory=directory;directory.mkdir(mode=0o700,parents=True,exist_ok=True)
        self.client=client;self.key=key;self.lock=threading.Lock()
    @contextlib.contextmanager
    def transaction(self):
        with self.lock, (self.directory/'.writer.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            try:yield
            finally:fcntl.flock(lock,fcntl.LOCK_UN)
    def head(self):
        heads=sorted(self.directory.glob('[0-9]*.json'))
        return json.loads(heads[-1].read_text()) if heads else dict(sequence=0,head='',publicKey=self.client)
    def receipt(self,nonce):
        payload={**self.head(),'nonce':nonce,'time':time.time()}
        raw=canonical(payload)
        return dict(payload=base64.b64encode(raw).decode(),signature=base64.b64encode(self.key.sign(raw)).decode())
    def append(self,evidence,expected):
        head=evidence['head']
        if head['publicKey']!=self.client:raise ValueError('Untrusted center key')
        client=serialization.load_der_public_key(base64.b64decode(self.client,validate=True))
        client.verify(base64.b64decode(evidence['signature'],validate=True),hashlib.sha256(canonical(head)).hexdigest().encode())
        with self.transaction():
            old=self.head()
            if old['sequence']!=expected or head['sequence']<expected or head['sequence']==expected and head['head']!=old['head']:raise ValueError('Head rollback/fork rejected')
            if head['sequence']>expected:
                number=old.get('anchorNumber',old['sequence'])+1
                record={**head,'anchorNumber':number}
                if old.get('recovery'):record['recovery']=old['recovery']
                self.save(record)
                fd=os.open(self.directory,os.O_RDONLY)
                try:os.fsync(fd)
                finally:os.close(fd)

    def save(self,record):
        path=self.directory/f"{record['anchorNumber']:020d}-{record['head']}.json"
        with tempfile.NamedTemporaryFile(prefix='.record-',dir=self.directory,delete=False) as out:
            temporary=Path(out.name);out.write(canonical(record));out.flush();os.fsync(out.fileno())
        try:os.link(temporary,path)
        finally:temporary.unlink(missing_ok=True)
    def authorize_recovery(self,evidence,reason,operator):
        if len(reason)<10 or not operator:raise ValueError('A named operator and recovery reason are required')
        head=evidence['head']
        if head['publicKey']!=self.client:raise ValueError('Wrong center signing identity')
        client=serialization.load_der_public_key(base64.b64decode(self.client,validate=True))
        client.verify(base64.b64decode(evidence['signature'],validate=True),hashlib.sha256(canonical(head)).hexdigest().encode())
        with self.transaction():
            previous=self.head()
            if head['sequence']>previous['sequence']:raise ValueError('Recovery checkpoint is newer than the trusted anchor')
            recovery=dict(id=uuid.uuid4().hex,reason=reason,operator=operator,time=time.time(),previous={k:previous[k]for k in ["sequence","head","publicKey","anchorNumber"]if k in previous},restored=head)
            # Global anchor numbers still increase. All earlier head records remain untouched.
            self.save({**head,'anchorNumber':previous.get('anchorNumber',previous['sequence'])+1,'recovery':recovery})
            fd=os.open(self.directory,os.O_RDONLY)
            try:os.fsync(fd)
            finally:os.close(fd)

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,status,value):
        raw=canonical(value);self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    def do_GET(self):
        if self.path!='/head':self.reply(404,{});return
        self.reply(200,self.server.receiver.receipt(self.headers.get('X-SyncSrv-Nonce','')))
    def do_POST(self):
        try:
            if self.path!='/head':raise ValueError('Unknown endpoint')
            length=int(self.headers.get('Content-Length','0'))
            if length<1 or length>16384:raise ValueError('Invalid request size')
            self.server.receiver.append(json.loads(self.rfile.read(length)),int(self.headers['If-Match']))
            self.reply(200,self.server.receiver.receipt(self.headers.get('X-SyncSrv-Nonce','')))
        except Exception:self.reply(409,dict(error='Head/signature conflict'))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);p.add_argument('--client-key',type=Path,required=True);p.add_argument('--receipt-key',type=Path,required=True);p.add_argument('--cert');p.add_argument('--tls-key');p.add_argument('--authorize-recovery',type=Path);p.add_argument('--reason');p.add_argument('--operator');p.add_argument('--bind',default='127.0.0.1');p.add_argument('--port',type=int,default=18019);a=p.parse_args()
    key=serialization.load_pem_private_key(a.receipt_key.read_bytes(),None)
    if not isinstance(key,Ed25519PrivateKey):raise ValueError('Ed25519 receipt key required')
    receiver=Receiver(a.directory,a.client_key.read_text().strip(),key)
    if a.authorize_recovery:
        receiver.authorize_recovery(json.loads(a.authorize_recovery.read_text()),a.reason or '',a.operator or '')
        print('Recovery authorized; earlier anchors retained');raise SystemExit(0)
    if not a.cert or not a.tls_key:p.error('--cert and --tls-key required for serving')
    server=ThreadingHTTPServer((a.bind,a.port),Handler);server.receiver=receiver
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2;context.load_cert_chain(a.cert,a.tls_key);server.socket=context.wrap_socket(server.socket,server_side=True);server.serve_forever()
