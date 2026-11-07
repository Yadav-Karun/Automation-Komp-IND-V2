#!/bin/bash
cd "/c/Testing/KOMP IND Testing"
java -cp "tools/recon/bin;$(cat tools/classpath.txt)" Recon "$@" 2>&1 | grep -v -E "^(SLF4J|WARNING|[A-Z][a-z]{2} [0-9]+, )|CDP|devtools|^s+at |^Build info|^System info|^Driver info|^Command:|^Capabilities|^Session ID"
