#!/usr/bin/env bash
set -e

ssh -v \
	-o "ExitOnForwardFailure yes" \
	-o "ServerAliveInterval 60" \
  -o "ServerAliveCountMax 5" \
  -o "StrictHostKeyChecking no" \
  -o "UserKnownHostsFile /dev/null" \
	-NT \
	-L 16443:localhost:16443 \
	-L 32000:localhost:32000 \
	oto@ogrodje-one <<EOF
	echo "Tunnel is active. Press Ctrl+C to exit.
EOF

