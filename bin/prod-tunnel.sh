#!/usr/bin/env bash
set -ex

ssh -vvv \
	-o "ExitOnForwardFailure yes" \
	-o "ServerAliveInterval 60" \
    -o "ServerAliveCountMax 5" \
    -o "StrictHostKeyChecking no" \
    -o "UserKnownHostsFile /dev/null" \
	-NT \
	-L 16443:localhost:16443 \
	oto@ogrodje-one <<EOF
	echo "Tunnel is active. Press Ctrl+C to exit.
EOF

