import base64,hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
spec=importlib.util.spec_from_file_location('anchor',Path(__file__).with_name('audit-anchor-server.py'));a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
class AnchorTest(unittest.TestCase):
 def test_monotonic_heads_and_explicit_recovery_keep_all_prior_evidence(self):
  client=Ed25519PrivateKey.generate();key=base64.b64encode(client.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
  def evidence(seq,hash):
   head=dict(sequence=seq,head=hash,publicKey=key,mode='LOCAL_SIGNED_CHAIN');return dict(head=head,signature=base64.b64encode(client.sign(hashlib.sha256(a.canonical(head)).hexdigest().encode())).decode())
  with tempfile.TemporaryDirectory()as temp:
   receiver=a.Receiver(Path(temp),key,Ed25519PrivateKey.generate());receiver.append(evidence(5,'a'*64),0)
   with self.assertRaises(ValueError):receiver.append(evidence(3,'b'*64),5)
   receiver.authorize_recovery(evidence(3,'b'*64),'Approved recovery after storage loss','named-operator')
   self.assertEqual(3,receiver.head()['sequence']);self.assertIn('recovery',receiver.head());receiver.append(evidence(4,'c'*64),3)
   records=sorted(Path(temp).glob('[0-9]*.json'));self.assertEqual(3,len(records));self.assertEqual([1,2,3],[json.loads(p.read_text())['anchorNumber']for p in records])
   raw=json.loads(base64.b64decode(receiver.receipt('fresh-nonce')['payload']));self.assertEqual('fresh-nonce',raw['nonce']);self.assertEqual('named-operator',raw['recovery']['operator'])
if __name__=='__main__':unittest.main()
