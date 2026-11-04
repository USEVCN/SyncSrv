"""Optional isolated SMB2 fixture: use a dedicated venv with impacket installed."""
import argparse
from impacket import smbserver, smb3structs
from impacket.nt_errors import STATUS_END_OF_FILE
from impacket.ntlm import compute_lmhash, compute_nthash

parser = argparse.ArgumentParser()
parser.add_argument("--root", required=True)
parser.add_argument("--port", type=int, required=True)
args = parser.parse_args()
server = smbserver.SimpleSMBServer(listenAddress="127.0.0.1", listenPort=args.port)
server.addShare("test", args.root)
server.setSMB2Support(True)
server.addCredential("test", 0, compute_lmhash("fixture-only"), compute_nthash("fixture-only"))
# impacket 0.13 returns a READ body alongside STATUS_END_OF_FILE. SMB clients
# correctly require an SMB2 ERROR body for that status. Correct only the fixture.
def read_with_error_body(conn_id, smb_server, packet):
    commands, packets, status = smbserver.SMB2Commands.smb2Read(conn_id, smb_server, packet)
    if status == STATUS_END_OF_FILE:
        commands = [smb3structs.SMB2Error()]
    return commands, packets, status

server.getServer().hookSmb2Command(smb3structs.SMB2_READ, read_with_error_body)
server.start()
