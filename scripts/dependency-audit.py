#!/usr/bin/env python3
"""Compatibility entry point for the complete artifact/source graph auditor."""
import runpy,sys
from pathlib import Path
root=Path(__file__).resolve().parent.parent
if '--jar' not in sys.argv:sys.argv.extend(['--jar',str(root/'Backend/target/syncsrv.jar')])
runpy.run_path(str(root/'scripts/source-dependency-audit.py'),run_name='__main__')
