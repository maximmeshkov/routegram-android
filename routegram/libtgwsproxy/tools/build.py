"""Portable Docker driver; installs nothing into the host Python environment."""
import argparse
from pathlib import Path
import subprocess

module = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--abi', action='append')
parser.add_argument('--jobs', type=int, default=4)
args = parser.parse_args()
output = module / 'build/pythonRuntime'
output.mkdir(parents=True, exist_ok=True)
image = 'routegram-python-builder:local'
subprocess.run(['docker', 'build', '-t', image, '-f', str(module / 'tools/Dockerfile'),
                str(module / 'tools')], check=True)
command = ['docker', 'run', '--rm',
           '--mount', f'type=bind,source={module},target=/module,readonly',
           '--mount', 'type=volume,source=routegram-python-build,target=/work',
           '--mount', f'type=bind,source={output},target=/out', image,
           '--jobs', str(args.jobs)]
for abi in args.abi or []:
    command += ['--abi', abi]
subprocess.run(command, check=True)
