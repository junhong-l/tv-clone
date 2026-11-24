#!/bin/bash
# tvclone 启动器：不用装包，直接用 python -m
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
exec python3 -c "import sys; sys.path.insert(0, '$HERE'); from tvclone.cli import main; sys.exit(main())" "$@"
