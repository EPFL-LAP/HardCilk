import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import { Buffer } from 'node:buffer'
import { spawn } from 'node:child_process'
import type { ServerResponse } from 'node:http'
import type { Plugin, PreviewServer, ViteDevServer } from 'vite'

type BandwidthStore = { cycles: Float64Array; read: Uint8Array; write: Uint8Array; length: number; portCount: number }
type StatusField = { encoding: 'boolean1' | 'readyValid2'; target?: { kind?: string; taskName?: string; index?: number; port?: string; signal?: string; lane?: number } }
type PeInfo = { peNumber: number; kind?: string; label?: string; task?: string; statusPrefix?: string; indexInTask?: number; fields?: StatusField[] }
type PortMaster = { owner: string; role?: string; peNumber: number | null; wData?: number; wId?: number }
type PortInfo = { port: number; portName?: string; masters: PortMaster[] }
type Descriptor = { design?: string; numComputePorts?: number; pes?: PeInfo[]; ports?: PortInfo[] }
type Segment = [number, number, number]
type BandwidthSample = [number, number, number]
type PortSeries = { read: BandwidthSample[]; write: BandwidthSample[] }
type Nicknames = Record<string, string>
// One extraction of `width` bits from the status word at `bit`, placed at `dest` in the row value.
type RowExtract = { bit: number; width: number; dest: number }
// 'hs' rows run the ready/valid state machine over their nibble, 'pt' rows pass the bits through.
type RowSpec = { mode: 'hs' | 'pt'; outputAccounting: boolean; extracts: RowExtract[] }
type RowPlan = { rows: RowSpec[]; descriptor: Descriptor | null; peCount: number; peIds: number[]; portCount: number; slotCount: number }
// A decoded trace, held open as file descriptors rather than as JavaScript objects: the viewer
// only ever needs the few thousand segments covering the current viewport, so segments live in
// the index directory and are read on demand from the coarsest level that still has the detail.
type TraceIndex = {
  dir: string
  rowCount: number
  portCount: number
  duration: number
  statusCount: number
  bandwidthCount: number
  streams: number[][]
  bw: BandwidthStore
  descriptor: Descriptor | null
  peCount: number
  peIds: number[]
  headerless: boolean
  fds: Map<string, number>
}

const ROOT = process.cwd()
const BUNDLES = path.resolve(ROOT, '../saved_bundles')
const NICKNAMES = path.join(BUNDLES, '.nicknames.json')
const NATIVE_DIR = path.join(ROOT, 'native', 'trace_decoder')
const NATIVE_BIN = path.join(NATIVE_DIR, 'target', 'release', process.platform === 'win32' ? 'trace_decoder.exe' : 'trace_decoder')
const INDEX_ROOT = path.resolve(ROOT, '.trace-index')
const INDEX_MAGIC = 'TVIDX004'
const INDEX_VERSION = 4
const DEFAULT_PE_COUNT = 12
// Matches LOD_FACTOR in the native decoder: each pyramid level covers 8 segments of the one below.
const LOD_FACTOR = 8
// Binary searches over an on-disk cycle column narrow to this many entries, then read one block.
const SEARCH_BLOCK = 1024
// Per row, a viewport reads at most this multiple of the requested point count.
const READ_BUDGET = 16
const OPEN_INDEX_LIMIT = 3
const CACHE_BYTES = Math.max(1, Number(process.env.TELEM_INDEX_CACHE_GB) || 48) * 1e9

const hasOutputAccounting = (pe: number, descriptor: Descriptor | null) => {
  const info = descriptor?.pes?.find(value => value.peNumber === pe)
  const task = info?.task
  if (info?.kind) return info.kind === 'pe' && (task === 'memReader' || Boolean(info.fields?.some(field => field.target?.kind === 'pe' && field.encoding === 'readyValid2' && field.target.port && field.target.port !== 'taskIn')))
  if (task) return !task.startsWith('internal:') && !task.startsWith('argumentCache:') && !task.startsWith('scheduler:') && (task === 'memReader' || task.includes('taskInitiator_reentry0'))
  return !descriptor && pe >= 4 && pe <= 7
}
const range = (count: number) => Array.from({ length: count }, (_, index) => index)
const peIdsFromDescriptor = (descriptor: Descriptor | null, peCount: number) => descriptor?.pes?.length ? [...new Set(descriptor.pes.map(pe => pe.peNumber))].sort((a, b) => a - b) : range(peCount)
const peDecodeCount = (descriptor: Descriptor | null) => {
  const ids = descriptor?.pes?.map(pe => pe.peNumber) || []
  return ids.length ? Math.max(...ids) + 1 : DEFAULT_PE_COUNT
}
const portDecodeCount = (descriptor: Descriptor | null) => {
  const descriptorCount = descriptor?.numComputePorts || 0
  const maxPort = descriptor?.ports?.length ? Math.max(...descriptor.ports.map(port => port.port)) + 1 : 0
  return Math.max(descriptorCount, maxPort, descriptor ? 0 : 31)
}
const fieldWidth = (field: StatusField) => field.encoding === 'readyValid2' ? 2 : 1
const fieldOffsets = (fields: StatusField[] = []) => {
  let offset = 0
  return fields.map(field => {
    const start = offset
    offset += fieldWidth(field)
    return start
  })
}
const targetKey = (target?: StatusField['target']) => target?.taskName !== undefined && target.index !== undefined ? `${target.taskName}:${target.index}` : ''
const parseOwnerPeKey = (owner: string) => {
  const [base] = owner.split('#')
  const [kind, task, index] = base.split(':')
  if (kind !== 'pe' || task === undefined || index === undefined) return ''
  const parsed = Number(index)
  return Number.isFinite(parsed) ? `${task}:${parsed}` : ''
}
const diagnosticGroupKey = (field: StatusField) => {
  const target = field.target
  if (!target?.kind) return 'unknown'
  const task = target.taskName || ''
  const index = target.index ?? 0
  if (target.kind === 'slowUpdateHandler' || target.kind === 'evictionSaver') return `slowEvict:${task}:${index}`
  if (target.kind === 'argumentServer') return `argumentServer:${task}:${index}`
  return `${target.kind}:${task}:${index}`
}
type FieldRef = { slot: number; offset: number; field: StatusField }

// Turns a descriptor into the list of rows the decoder should emit. Descriptors that only expose
// packed status slots get unpacked here into one row per logical PE plus grouped diagnostic rows;
// the decoder then writes those rows directly, so no post-pass over decoded segments is needed.
function buildRowPlan(descriptor: Descriptor | null): RowPlan {
  const slotCount = peDecodeCount(descriptor)
  const portCount = portDecodeCount(descriptor)
  const sourcePes = descriptor?.pes || []
  const hasLogicalPeRows = sourcePes.some(pe => pe.kind === 'pe')
  const hasPackedPeFields = sourcePes.some(pe => pe.fields?.some(field => field.target?.kind === 'pe'))
  if (!descriptor || hasLogicalPeRows || !hasPackedPeFields) {
    const rows: RowSpec[] = range(slotCount).map(pe => ({
      mode: 'hs',
      outputAccounting: hasOutputAccounting(pe, descriptor),
      extracts: [{ bit: pe * 4, width: 4, dest: 0 }],
    }))
    return { rows, descriptor, peCount: slotCount, peIds: peIdsFromDescriptor(descriptor, slotCount), portCount, slotCount }
  }

  const logical = new Map<string, { task: string; index: number; input?: FieldRef; output?: FieldRef }>()
  const diagnosticRows: Array<{ source: PeInfo; fields: Array<{ field: StatusField; offset: number }>; kind: 'internal' | 'congestion' }> = []

  for (const source of sourcePes) {
    const offsets = fieldOffsets(source.fields)
    const internalGroups = new Map<string, Array<{ field: StatusField; offset: number }>>()
    const congestionFields: Array<{ field: StatusField; offset: number }> = []
    source.fields?.forEach((field, index) => {
      const target = field.target
      const offset = offsets[index]
      if (field.encoding === 'readyValid2' && target?.kind === 'pe' && target.taskName !== undefined && target.index !== undefined) {
        const key = targetKey(target)
        const item = logical.get(key) || { task: target.taskName, index: target.index }
        const ref = { slot: source.peNumber, offset, field }
        if (target.port === 'taskIn') item.input = ref
        else item.output ??= ref
        logical.set(key, item)
      } else if (field.encoding === 'boolean1' && target?.kind === 'schedulerServer') {
        congestionFields.push({ field, offset })
      } else if (field.encoding === 'readyValid2' && target?.kind && target.kind !== 'pe') {
        const key = diagnosticGroupKey(field)
        const group = internalGroups.get(key) || []
        group.push({ field, offset })
        internalGroups.set(key, group)
      }
    })
    for (const fields of internalGroups.values()) diagnosticRows.push({ source, fields, kind: 'internal' })
    if (congestionFields.length) diagnosticRows.push({ source, fields: congestionFields, kind: 'congestion' })
  }

  const rows: RowSpec[] = []
  const newPes: PeInfo[] = []
  const logicalNumber = new Map<string, number>()

  for (const item of logical.values()) {
    const peNumber = newPes.length
    const extracts: RowExtract[] = []
    if (item.input) extracts.push({ bit: item.input.slot * 4 + item.input.offset, width: 2, dest: 0 })
    if (item.output) extracts.push({ bit: item.output.slot * 4 + item.output.offset, width: 2, dest: 2 })
    rows.push({ mode: 'hs', outputAccounting: Boolean(item.output), extracts })
    logicalNumber.set(`${item.task}:${item.index}`, peNumber)
    newPes.push({
      peNumber,
      kind: 'pe',
      label: `${item.task}${item.index}`,
      task: item.task,
      indexInTask: item.index,
      fields: [item.input?.field, item.output?.field].filter((field): field is StatusField => Boolean(field)),
    })
  }

  for (const row of diagnosticRows) {
    const peNumber = newPes.length
    const extracts: RowExtract[] = []
    let dest = 0
    for (const { field, offset } of row.fields) {
      const width = fieldWidth(field)
      extracts.push({ bit: row.source.peNumber * 4 + offset, width, dest })
      dest += width
    }
    rows.push({ mode: 'pt', outputAccounting: false, extracts })
    newPes.push({
      peNumber,
      kind: 'packed',
      label: row.source.label,
      task: row.kind === 'congestion' ? 'scheduler:packed' : row.source.task,
      statusPrefix: row.kind === 'congestion' ? 'sched_congested' : row.source.statusPrefix,
      indexInTask: row.source.indexInTask,
      fields: row.fields.map(({ field }) => field),
    })
  }

  const next: Descriptor = {
    ...descriptor,
    pes: newPes,
    ports: descriptor.ports?.map(port => ({
      ...port,
      masters: port.masters.map(master => {
        if (master.peNumber !== null) return master
        const mapped = logicalNumber.get(parseOwnerPeKey(master.owner))
        return mapped === undefined ? master : { ...master, peNumber: mapped }
      }),
    })),
  }
  return { rows, descriptor: next, peCount: newPes.length, peIds: peIdsFromDescriptor(next, newPes.length), portCount, slotCount }
}

const serializePlan = (plan: RowPlan) => [
  `peCount ${plan.slotCount}`,
  `portCount ${plan.portCount}`,
  ...plan.rows.map(row => `row ${row.mode} ${row.outputAccounting ? 1 : 0} ${row.extracts.map(extract => `${extract.bit}:${extract.width}:${extract.dest}`).join(',')}`),
].join('\n') + '\n'

const clients = new Set<ServerResponse>()
const json = (response: ServerResponse, value: unknown, status = 200) => { response.statusCode = status; response.setHeader('content-type', 'application/json'); response.end(JSON.stringify(value)) }
const names = (): Nicknames => { try { return JSON.parse(fs.readFileSync(NICKNAMES, 'utf8')) as Nicknames } catch { return {} } }
// Read only the 16-byte fixed header to learn provenance (flags bit 0 = is_emulation, i.e. a
// Vitis hw_emu/sw_emu simulation). A headerless file (no magic) has no flags ⇒ real hardware.
const isSimulation = (file: string) => {
  const fileDescriptor = fs.openSync(file, 'r')
  try {
    const header = Buffer.alloc(16)
    fs.readSync(fileDescriptor, header, 0, header.length, 0)
    if (header.subarray(0, 8).toString('ascii') !== 'HCKTRACE') return false
    return Boolean(header.readUInt32LE(12) & 1)
  } catch { return false } finally { fs.closeSync(fileDescriptor) }
}
const traces = () => {
  const nicknames = names()
  return fs.readdirSync(BUNDLES, { withFileTypes: true }).filter(entry => entry.isFile() && entry.name.endsWith('.bin')).map(entry => {
    const stat = fs.statSync(path.join(BUNDLES, entry.name))
    return { id: entry.name, name: entry.name.replace(/\.bin$/, ''), nickname: nicknames[entry.name] || '', size: stat.size, sizeLabel: stat.size > 1e9 ? `${(stat.size / 1e9).toFixed(1)} GB` : `${(stat.size / 1e6).toFixed(1)} MB`, modified: stat.mtimeMs, simulation: isSimulation(path.join(BUNDLES, entry.name)) }
  }).sort((a, b) => b.modified - a.modified)
}

function readHeader(file: string) {
  const fileDescriptor = fs.openSync(file, 'r')
  const header = Buffer.alloc(32)
  try {
    fs.readSync(fileDescriptor, header, 0, header.length, 0)
    if (header.subarray(0, 8).toString('ascii') !== 'HCKTRACE') return { descriptor: null, version: 1, beatsOffset: 0, headerless: true }
    const version = header.readUInt32LE(8)
    const jsonLength = Number(header.readBigUInt64LE(16))
    const beatsOffset = Number(header.readBigUInt64LE(24))
    const json = Buffer.alloc(jsonLength)
    fs.readSync(fileDescriptor, json, 0, json.length, 32)
    return { descriptor: JSON.parse(json.toString('utf8')) as Descriptor, version, beatsOffset, headerless: false }
  } finally { fs.closeSync(fileDescriptor) }
}

let nativeBuild: Promise<void> | null = null

function newerThan(target: string, source: string) {
  try { return fs.statSync(source).mtimeMs > fs.statSync(target).mtimeMs } catch { return true }
}

function run(command: string, args: string[], options: { cwd?: string } = {}) {
  return new Promise<void>((resolve, reject) => {
    const child = spawn(command, args, { cwd: options.cwd, stdio: ['ignore', 'inherit', 'pipe'] })
    const stderr: Buffer[] = []
    child.stderr.on('data', chunk => stderr.push(chunk))
    child.on('error', reject)
    child.on('close', code => {
      const errorText = Buffer.concat(stderr).toString('utf8')
      if (code === 0) resolve()
      else reject(new Error(`${command} ${args.join(' ')} failed with exit code ${code}${errorText ? `\n${errorText}` : ''}`))
    })
  })
}

async function ensureNativeDecoder() {
  const source = path.join(NATIVE_DIR, 'src', 'main.rs')
  if (fs.existsSync(NATIVE_BIN) && !newerThan(NATIVE_BIN, source) && !newerThan(NATIVE_BIN, path.join(NATIVE_DIR, 'Cargo.toml'))) return
  nativeBuild ??= run('cargo', ['build', '--release', '--manifest-path', path.join(NATIVE_DIR, 'Cargo.toml')]).then(() => { nativeBuild = null }, error => { nativeBuild = null; throw error })
  await nativeBuild
}

function readMeta(dir: string) {
  const buffer = fs.readFileSync(path.join(dir, 'meta.bin'))
  if (buffer.subarray(0, 8).toString('ascii') !== INDEX_MAGIC) throw new Error('Native decoder wrote an invalid index')
  let offset = 8
  const version = buffer.readUInt32LE(offset); offset += 4
  if (version !== INDEX_VERSION) throw new Error(`Unsupported native index version ${version}`)
  const streamCount = buffer.readUInt32LE(offset); offset += 4
  const rowCount = buffer.readUInt32LE(offset); offset += 4
  const portCount = buffer.readUInt32LE(offset); offset += 4
  offset += 4
  const duration = Number(buffer.readBigUInt64LE(offset)); offset += 8
  const statusCount = Number(buffer.readBigUInt64LE(offset)); offset += 8
  const bandwidthCount = Number(buffer.readBigUInt64LE(offset)); offset += 8
  const streams: number[][] = []
  for (let stream = 0; stream < streamCount; stream++) {
    const levelCount = buffer.readUInt32LE(offset); offset += 4
    const levels: number[] = []
    for (let level = 0; level < levelCount; level++) { levels.push(Number(buffer.readBigUInt64LE(offset))); offset += 8 }
    streams.push(levels)
  }
  return { rowCount, portCount, duration, statusCount, bandwidthCount, streams }
}

function readWhole(file: string, target: Buffer) {
  const fileDescriptor = fs.openSync(file, 'r')
  try {
    let read = 0
    while (read < target.length) {
      const chunk = fs.readSync(fileDescriptor, target, read, target.length - read, read)
      if (!chunk) break
      read += chunk
    }
  } finally { fs.closeSync(fileDescriptor) }
}

function readBandwidth(dir: string, count: number, portCount: number): BandwidthStore {
  const cycles = new Float64Array(count)
  const read = new Uint8Array(count * portCount)
  const write = new Uint8Array(count * portCount)
  if (count) {
    readWhole(path.join(dir, 'bw.c'), Buffer.from(cycles.buffer))
    readWhole(path.join(dir, 'bw.r'), Buffer.from(read.buffer))
    readWhole(path.join(dir, 'bw.w'), Buffer.from(write.buffer))
  }
  return { cycles, read, write, length: count, portCount }
}

function bandwidthCycle(store: BandwidthStore, index: number) {
  return store.cycles[index]
}

function bandwidthValue(store: BandwidthStore, index: number, port: number, direction: 'read' | 'write') {
  return store[direction][index * store.portCount + port]
}

function bandwidthLowerBound(store: BandwidthStore, value: number) {
  let low = 0, high = store.length
  while (low < high) {
    const middle = Math.floor((low + high) / 2)
    if (store.cycles[middle] < value) low = middle + 1
    else high = middle
  }
  return low
}

function streamFd(index: TraceIndex, stream: number, level: number, kind: 'f' | 's') {
  const key = `${stream}.${level}.${kind}`
  let fileDescriptor = index.fds.get(key)
  if (fileDescriptor === undefined) {
    fileDescriptor = fs.openSync(path.join(index.dir, `s${stream}.l${level}.${kind}`), 'r')
    index.fds.set(key, fileDescriptor)
  }
  return fileDescriptor
}

function closeIndex(index: TraceIndex) {
  for (const fileDescriptor of index.fds.values()) { try { fs.closeSync(fileDescriptor) } catch { /* already gone */ } }
  index.fds.clear()
}

const cycleBuffer = Buffer.alloc(4)
function cycleAt(fileDescriptor: number, position: number) {
  fs.readSync(fileDescriptor, cycleBuffer, 0, 4, position * 4)
  return cycleBuffer.readUInt32LE(0)
}

function readCycles(fileDescriptor: number, first: number, count: number) {
  const buffer = Buffer.alloc(count * 4)
  let read = 0
  while (read < buffer.length) {
    const chunk = fs.readSync(fileDescriptor, buffer, read, buffer.length - read, first * 4 + read)
    if (!chunk) break
    read += chunk
  }
  return new Uint32Array(buffer.buffer, buffer.byteOffset, count)
}

function readStates(fileDescriptor: number, first: number, count: number) {
  const buffer = Buffer.alloc(count)
  let read = 0
  while (read < count) {
    const chunk = fs.readSync(fileDescriptor, buffer, read, count - read, first + read)
    if (!chunk) break
    read += chunk
  }
  return buffer
}

// Segments tile the timeline, so the cycle column holds `count + 1` starts and segment i spans
// [from[i], from[i + 1]). `shift` picks which end the predicate looks at: 0 compares against a
// segment's start, 1 against its end.
function boundary(fileDescriptor: number, count: number, shift: number, value: number, strict: boolean) {
  const passed = (cycle: number) => strict ? cycle > value : cycle >= value
  let low = 0, high = count
  while (high - low > SEARCH_BLOCK) {
    const middle = (low + high) >>> 1
    if (passed(cycleAt(fileDescriptor, middle + shift))) high = middle
    else low = middle + 1
  }
  if (low >= high) return low
  const window = readCycles(fileDescriptor, low + shift, high - low)
  for (let offset = 0; offset < window.length; offset++) if (passed(window[offset])) return low + offset
  return high
}

/// Reads one row of the viewport, picking the coarsest pyramid level that still resolves more
/// detail than the caller asked for. Mirrors the striding and merging the viewer used to do over
/// fully materialised segments.
function sliceStream(index: TraceIndex, stream: number, start: number, end: number, points: number): Segment[] {
  const levels = index.streams[stream]
  if (!levels?.length || !levels[0]) return []
  const budget = Math.max(points, points * READ_BUDGET)
  const span = Math.max(1, end - start)
  const fraction = Math.min(1, span / Math.max(1, index.duration))
  let level = levels.length - 1
  for (let candidate = 0; candidate < levels.length; candidate++) {
    if (levels[candidate] * fraction <= budget) { level = candidate; break }
  }
  let first = 0, last = 0
  const locate = () => {
    const fileDescriptor = streamFd(index, stream, level, 'f')
    const count = levels[level]
    first = boundary(fileDescriptor, count, 1, start, false)
    last = Math.max(first, boundary(fileDescriptor, count, 0, end, true))
  }
  locate()
  // The estimate above assumes an even spread of segments; correct it against what is really there.
  while (last - first > budget && level < levels.length - 1) { level++; locate() }
  while (level > 0 && (last - first) * LOD_FACTOR <= budget) { level--; locate() }

  const count = last - first
  if (count <= 0) return []
  const cycles = readCycles(streamFd(index, stream, level, 'f'), first, count + 1)
  const states = readStates(streamFd(index, stream, level, 's'), first, count)
  const step = Math.max(1, Math.ceil(count / points / 8))
  const result: Segment[] = []
  for (let i = 0; i < count; i += step) {
    const from = Math.max(start, cycles[i])
    const to = Math.min(end, cycles[Math.min(count - 1, i + step - 1) + 1])
    const state = states[i]
    const previous = result.at(-1)
    if (previous && previous[2] === state && previous[1] === from) previous[1] = Math.max(from, to)
    else result.push([from, Math.max(from, to), state])
  }
  return result
}

function viewport(trace: TraceIndex, start: number, end: number, points: number) {
  const status = range(trace.rowCount).map(row => sliceStream(trace, row * 2, start, end, points))
  const rawStatus = range(trace.rowCount).map(row => sliceStream(trace, row * 2 + 1, start, end, points))
  const series: PortSeries[] = Array.from({ length: trace.portCount }, () => ({ read: [], write: [] }))
  const firstBandwidth = bandwidthLowerBound(trace.bw, start - 128)
  let lastBandwidth = bandwidthLowerBound(trace.bw, end + 129)
  lastBandwidth = Math.min(trace.bw.length, Math.max(firstBandwidth, lastBandwidth))
  const relevantCount = lastBandwidth - firstBandwidth
  const stride = relevantCount <= 15_000 ? 1 : Math.max(1, Math.ceil(relevantCount / points))
  let maxBandwidth = 1
  for (let i = firstBandwidth; i < lastBandwidth; i += stride) {
    const groupEnd = Math.min(lastBandwidth, i + stride)
    const windowStart = bandwidthCycle(trace.bw, i)
    const windowEnd = bandwidthCycle(trace.bw, groupEnd - 1) + 128
    const groupLength = groupEnd - i
    for (let port = 0; port < trace.portCount; port++) {
      let read = 0, write = 0
      for (let recordIndex = i; recordIndex < groupEnd; recordIndex++) {
        read += bandwidthValue(trace.bw, recordIndex, port, 'read')
        write += bandwidthValue(trace.bw, recordIndex, port, 'write')
      }
      read /= groupLength; write /= groupLength
      series[port].read.push([windowStart, windowEnd, read]); series[port].write.push([windowStart, windowEnd, write]); maxBandwidth = Math.max(maxBandwidth, read, write)
    }
  }
  return { status, rawStatus, bandwidth: series, maxBandwidth, duration: trace.duration, start, end }
}

const directorySize = (dir: string) => {
  let total = 0
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile()) { try { total += fs.statSync(path.join(dir, entry.name)).size } catch { /* raced with a prune */ } }
  }
  return total
}

// Indexes are several times the size of a bundle, so keep the cache bounded and drop the
// least recently used directories first.
function pruneIndexCache(keep: Set<string>) {
  let entries: fs.Dirent[]
  try { entries = fs.readdirSync(INDEX_ROOT, { withFileTypes: true }) } catch { return }
  const stored = entries.filter(entry => entry.isDirectory() && !entry.name.startsWith('.')).map(entry => {
    const dir = path.join(INDEX_ROOT, entry.name)
    let used = 0
    try { used = fs.statSync(dir).mtimeMs } catch { /* raced */ }
    return { name: entry.name, dir, used, size: directorySize(dir) }
  })
  let total = stored.reduce((sum, entry) => sum + entry.size, 0)
  if (total <= CACHE_BYTES) return
  for (const entry of stored.sort((a, b) => a.used - b.used)) {
    if (total <= CACHE_BYTES) break
    if (keep.has(entry.dir)) continue
    fs.rmSync(entry.dir, { recursive: true, force: true })
    total -= entry.size
  }
}

const openIndexes = new Map<string, TraceIndex>()
const building = new Map<string, Promise<TraceIndex>>()

async function buildIndex(file: string, plan: RowPlan, dir: string) {
  await ensureNativeDecoder()
  const staging = `${dir}.${process.pid}.${crypto.randomBytes(4).toString('hex')}`
  fs.mkdirSync(staging, { recursive: true })
  try {
    const planFile = path.join(staging, 'plan.txt')
    fs.writeFileSync(planFile, serializePlan(plan))
    await run(NATIVE_BIN, [file, planFile, staging])
    fs.rmSync(dir, { recursive: true, force: true })
    fs.renameSync(staging, dir)
  } catch (error) {
    fs.rmSync(staging, { recursive: true, force: true })
    throw error
  }
}

async function openTrace(file: string): Promise<TraceIndex> {
  const stat = fs.statSync(file)
  const headerInfo = readHeader(file)
  const plan = buildRowPlan(headerInfo.descriptor)
  const planText = serializePlan(plan)
  const key = crypto.createHash('sha1').update(`${path.resolve(file)}|${stat.size}|${stat.mtimeMs}|${INDEX_VERSION}|${planText}`).digest('hex')
  const existing = openIndexes.get(key)
  if (existing) { openIndexes.delete(key); openIndexes.set(key, existing); return existing }
  const pending = building.get(key)
  if (pending) return pending

  const dir = path.join(INDEX_ROOT, key)
  const task = (async () => {
    fs.mkdirSync(INDEX_ROOT, { recursive: true })
    if (!fs.existsSync(path.join(dir, 'meta.bin'))) await buildIndex(file, plan, dir)
    const now = new Date()
    try { fs.utimesSync(dir, now, now) } catch { /* best effort LRU stamp */ }
    const meta = readMeta(dir)
    const index: TraceIndex = {
      dir,
      rowCount: meta.rowCount,
      portCount: meta.portCount,
      duration: meta.duration,
      statusCount: meta.statusCount,
      bandwidthCount: meta.bandwidthCount,
      streams: meta.streams,
      bw: readBandwidth(dir, meta.bandwidthCount, meta.portCount),
      descriptor: plan.descriptor,
      peCount: plan.peCount,
      peIds: plan.peIds,
      headerless: headerInfo.headerless,
      fds: new Map(),
    }
    openIndexes.set(key, index)
    while (openIndexes.size > OPEN_INDEX_LIMIT) {
      const [oldestKey, oldest] = openIndexes.entries().next().value as [string, TraceIndex]
      openIndexes.delete(oldestKey)
      closeIndex(oldest)
    }
    pruneIndexCache(new Set([...openIndexes.values()].map(entry => entry.dir)))
    return index
  })()
  building.set(key, task)
  try { return await task } finally { building.delete(key) }
}

function dropStaleIndexes() {
  for (const [key, index] of openIndexes) {
    if (fs.existsSync(path.join(index.dir, 'meta.bin'))) continue
    openIndexes.delete(key)
    closeIndex(index)
  }
}

function configureTelemetryServer(server: ViteDevServer | PreviewServer) {
    fs.mkdirSync(BUNDLES, { recursive: true })
    const notify = () => clients.forEach(response => response.write('data: update\n\n'))
    let signature = traces().map(trace => `${trace.id}:${trace.size}:${trace.modified}`).join('|')
    const watcher = setInterval(() => {
      const next = traces().map(trace => `${trace.id}:${trace.size}:${trace.modified}`).join('|')
      if (next !== signature) { signature = next; dropStaleIndexes(); notify() }
    }, 500)
    watcher.unref(); server.httpServer?.once('close', () => { clearInterval(watcher); for (const index of openIndexes.values()) closeIndex(index); openIndexes.clear() })
    server.middlewares.use(async (request, response, next) => { try {
      const url = new URL(request.url || '/', 'http://local')
      const parts = url.pathname.split('/').filter(Boolean)
      if (url.pathname === '/api/traces' && request.method === 'GET') return json(response, traces())
      if (url.pathname === '/api/events') { response.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' }); response.write('data: ready\n\n'); clients.add(response); request.on('close', () => clients.delete(response)); return }
      if (parts[0] === 'api' && parts[1] === 'traces' && parts[2]) {
        const id = decodeURIComponent(parts[2])
        if (path.basename(id) !== id) return json(response, { error: 'invalid trace' }, 400)
        const file = path.join(BUNDLES, id)
        if (!fs.existsSync(file)) return json(response, { error: 'not found' }, 404)
        if (parts[3] === 'nickname' && request.method === 'PUT') { let body = ''; for await (const chunk of request) body += chunk; const nicknames = names(), value = (JSON.parse(body) as { nickname?: string }).nickname?.trim() || ''; if (value) nicknames[id] = value; else delete nicknames[id]; fs.writeFileSync(NICKNAMES, JSON.stringify(nicknames, null, 2)); notify(); return json(response, { ok: true }) }
        const trace = await openTrace(file)
        if (parts[3] === 'meta') return json(response, { duration: trace.duration, statusCount: trace.statusCount, bandwidthCount: trace.bandwidthCount, portCount: trace.portCount, peCount: trace.peCount, peIds: trace.peIds, descriptor: trace.descriptor, headerless: trace.headerless })
        if (parts[3] === 'sample') {
          const requested = Number(url.searchParams.get('cycle')) || 64
          if (!trace.bw.length) return json(response, { cycle: requested, read: Array(trace.portCount).fill(0), write: Array(trace.portCount).fill(0) })
          let low = 0, high = trace.bw.length
          while (low < high) { const middle = Math.floor((low + high) / 2); if (bandwidthCycle(trace.bw, middle) + 64 < requested) low = middle + 1; else high = middle }
          const before = Math.max(0, low - 1)
          const after = Math.min(trace.bw.length - 1, low)
          const sampleIndex = Math.abs(bandwidthCycle(trace.bw, after) + 64 - requested) < Math.abs(bandwidthCycle(trace.bw, before) + 64 - requested) ? after : before
          const read = Array.from({ length: trace.portCount }, (_, port) => bandwidthValue(trace.bw, sampleIndex, port, 'read'))
          const write = Array.from({ length: trace.portCount }, (_, port) => bandwidthValue(trace.bw, sampleIndex, port, 'write'))
          return json(response, { cycle: bandwidthCycle(trace.bw, sampleIndex) + 64, read, write })
        }
        if (parts[3] === 'data') { const start = Number(url.searchParams.get('start')) || 0, end = Number(url.searchParams.get('end')) || trace.duration, points = Math.min(5000, Math.max(200, Number(url.searchParams.get('points')) || 1200)); return json(response, viewport(trace, start, end, points)) }
      }
      next()
    } catch (error) { console.error(error); json(response, { error: error instanceof Error ? error.message : 'Unknown error' }, 500) } })
}

export function telemetryPlugin(): Plugin {
  return {
    name: 'telemetry-api',
    configureServer: configureTelemetryServer,
    configurePreviewServer: configureTelemetryServer,
  }
}
