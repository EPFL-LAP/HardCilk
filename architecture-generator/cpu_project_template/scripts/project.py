#!/usr/bin/env python3
"""Build a generated HardCilk CPU project. Application sources live separately
from generated infrastructure; command arguments never pass through a shell.
"""
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
from compose import compose

ROOT = Path(__file__).resolve().parents[1]
PROJECT = json.loads((ROOT / 'project.json').read_text())
BUILD = ROOT / 'build'
PREFIX = Path(os.environ.get('HDLSTUFF_PREFIX', str(Path.home() / '.local/opt/hdlstuff')))
ENV = os.environ.copy()
ENV['PATH'] = str(PREFIX / 'bin') + ':/opt/riscv/bin:' + ENV.get('PATH', '')

def run(argv, name, cwd=ROOT):
    logs = BUILD / 'logs'; logs.mkdir(parents=True, exist_ok=True)
    print('+ ' + shlex.join(map(str, argv)), flush=True)
    with (logs / (name + '.log')).open('w') as log:
        result = subprocess.run(list(map(str, argv)), cwd=cwd, env=ENV, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((logs / (name + '.log')).read_text()[-12000:], file=sys.stderr)
        raise RuntimeError(f'{name} failed; see build/logs/{name}.log')

def repo(variable, field, required):
    value = os.environ.get(variable, PROJECT.get(field, ''))
    path = Path(value).resolve() if value else None
    if path is None or not (path / required).is_file():
        raise RuntimeError(f'Set {variable} to the checkout containing {required}')
    return path

def sbt(cwd, main, args, name):
    # sbt parses runMain's argument string itself; quote paths for that parser.
    quote = lambda s: '"' + str(s).replace('\\', '\\\\').replace('"', '\\"') + '"'
    command = 'runMain ' + main + ' ' + ' '.join(quote(a) for a in args)
    run([os.environ.get('SBT', 'sbt'), '-Dsbt.ivy.home=' + str(PREFIX / '.ivy2'), '-batch', command], name, cwd)

def cpu_tasks():
    return [t for t in PROJECT['tasks'] if t.get('kind', 'cpu') == 'cpu']

def firmware():
    destination = BUILD / 'firmware'; destination.mkdir(parents=True, exist_ok=True)
    for task in cpu_tasks():
        name = task['name']; source = ROOT / 'src/cpu' / name
        sources = sorted(p for p in source.rglob('*') if p.suffix in ('.c', '.cpp', '.S'))
        if not sources:
            raise RuntimeError(f'No firmware in src/cpu/{name}; add a worker() implementation to your application template')
        elf = destination / (name + '.elf')
        run([os.environ.get('RISCV_CC', 'riscv64-unknown-linux-gnu-gcc'),
             '-march=rv64if' if task.get('floatingPoint') else '-march=rv64i', '-mabi=lp64', '-Os', '-ffreestanding', '-fno-pic', '-fno-pie',
             '-fno-jump-tables', '-nostdlib', '-nostartfiles', '-msmall-data-limit=0',
             '-I' + str(ROOT / 'src/cpu/generated' / name), '-I' + str(source),
             '-Wl,-T,' + str(ROOT / 'config/cpu' / (name + '.ld')), '-Wl,--build-id=none',
             ROOT / 'scripts/start.S', *sources, '-o', elf], 'firmware-' + name)
        run([os.environ.get('RISCV_OBJCOPY', 'riscv64-unknown-linux-gnu-objcopy'),
             '-O', 'binary', elf, destination / (name + '.bin')], 'objcopy-' + name)

def rtl(force=False):
    runtime = ROOT / 'src/rtl' / PROJECT['name']
    architecture = json.loads((ROOT / 'architecture.json').read_text())
    runtime_ready = ((runtime / (PROJECT['name'] + '.sv')).is_file() and
                     (runtime / 'architecture.json').is_file() and
                     json.loads((runtime / 'architecture.json').read_text()) == architecture)
    if force or not runtime_ready:
        hardcilk = repo('HARDCILK_REPO', 'hardcilkRepo', 'architecture-generator/build.sbt')
        sbt(hardcilk / 'architecture-generator', 'CpuIntegration.Emit',
            [ROOT / 'architecture.json', runtime], 'runtime-elaboration')
    # A previously built project can be moved and simulated without either checkout.
    cpu_path = None
    shared = ROOT / 'src/rtl/common'
    known = {}
    # Memory readers are emitted from the CPU checkout as well.
    emitters = {'cpu': ('cpu', 'HardCilkCpu', 'cpu.finegrained.HardCilkCpuEmit'),
                'memoryReader': ('reader', 'HardCilkMemoryReader', 'cpu.finegrained.HardCilkMemoryReaderEmit')}
    for task in PROJECT['tasks']:
        folder, module, main = emitters[task.get('kind', 'cpu')]
        name = task['name']; config = ROOT / 'config' / folder / (name + '.json')
        key = hashlib.sha256(config.read_bytes()).hexdigest()
        output = ROOT / 'src/rtl' / folder / name
        stamp = output / 'config.sha256'
        cached = stamp.is_file() and stamp.read_text() == key and (output / (module + '.sv')).is_file()
        if force or not cached:
            if (folder, key) in known:
                shutil.copytree(known[(folder, key)], output, dirs_exist_ok=True)
            else:
                if cpu_path is None:
                    cpu_path = repo('CPU_REPO', 'cpuRepo', 'src/main/scala/cpu/finegrained/HardCilkCpu.scala')
                sbt(cpu_path, main, [config, output], folder + '-' + name)
            stamp.write_text(key)
        known[(folder, key)] = output
    if cpu_path is not None or not (shared / 'DualPortBRAM_sim.v').is_file() or not (ROOT / 'src/host/generated/include/hardcilk_boot.hpp').is_file():
        if cpu_path is None:
            cpu_path = repo('CPU_REPO', 'cpuRepo', 'src/main/scala/cpu/finegrained/HardCilkCpu.scala')
        shared.mkdir(parents=True, exist_ok=True)
        shutil.copytree(cpu_path / 'verilog', shared, dirs_exist_ok=True)
        shutil.copy2(cpu_path / 'src/main/resources/DualPortBRAM_sim.v', shared)
        shutil.copy2(cpu_path / 'software/finegrained/hardcilk_boot.hpp', ROOT / 'src/host/generated/include')
    compose(ROOT)

def host():
    firmware(); rtl()
    applications = ROOT / 'src/host/projects'
    choices = sorted(p.name for p in applications.iterdir() if p.is_dir()) if applications.is_dir() else []
    app = os.environ.get('APP') or (choices[0] if len(choices) == 1 else '')
    if not app or app not in choices:
        raise RuntimeError(f'Set APP to a src/host/projects application (available: {choices})')
    sources = sorted((applications / app / 'src').rglob('*.cpp'))
    if not sources:
        raise RuntimeError(f'No host application in src/host/projects/{app}/src')
    (BUILD / 'host' / app).mkdir(parents=True, exist_ok=True)
    system = ROOT / 'src/rtl/system'
    cflags = shlex.join(['-std=c++17', '-I' + str(ROOT / 'src/host/runtime'),
                       '-I' + str(ROOT / 'src/host/generated/include'),
                       '-I' + str(applications / app / 'include')])
    run([os.environ.get('VERILATOR', 'verilator'), '--cc', '--exe', '--build',
         '-j', os.environ.get('JOBS', '4'), '-MAKEFLAGS', 'CFG_CXXFLAGS_PCH_I=-include',
         '--assert', '-Wno-fatal', '--top-module', 'System',
         '--Mdir', BUILD / 'host' / app, '-CFLAGS', cflags,
         '-I' + str(ROOT / 'src/rtl/common'), system / 'System.sv',
         *[system / (t['name'] + '.sv') for t in PROJECT['tasks']],
         ROOT / 'src/rtl' / PROJECT['name'] / (PROJECT['name'] + '.sv'),
         ROOT / 'src/rtl/common/DualPortBRAM_sim.v', *sources], 'host-' + app)
    return BUILD / 'host' / app / 'VSystem'

def main():
    action = sys.argv[1] if len(sys.argv) == 2 else 'help'
    if action == 'clean':
        if BUILD.exists(): shutil.rmtree(BUILD)
    elif action == 'firmware': firmware()
    elif action == 'rtl': rtl(force=True)
    elif action in ('host', 'sim'):
        executable = host()
        if action == 'sim':
            for seed in os.environ.get('SEEDS', '1 42').split():
                subprocess.run([str(executable), str(ROOT), seed], cwd=ROOT, env=ENV, check=True)
    else: raise RuntimeError('Usage: project.py firmware|rtl|host|sim|clean')

if __name__ == '__main__':
    try: main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
