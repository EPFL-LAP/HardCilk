import { type FormEvent, type PointerEvent, type RefObject, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import './App.css'

type Range = [number, number]
type Segment = [number, number, number]
type BandwidthSample = [number, number, number]
type TraceRow = { id: string; name: string; nickname: string; size: number; sizeLabel: string; modified: number; simulation: boolean }
type StatusField = { encoding: 'boolean1' | 'readyValid2'; target?: { kind?: string; taskName?: string; index?: number; port?: string; signal?: string; lane?: number } }
type PeInfo = { peNumber: number; kind?: string; label?: string; task?: string; statusPrefix?: string; indexInTask?: number; fields?: StatusField[] }
type PortMaster = { owner: string; role?: string; peNumber: number | null; wData?: number; wId?: number }
type PortInfo = { port: number; portName?: string; masters: PortMaster[] }
type TraceDescriptor = { design?: string; numComputePorts?: number; pes?: PeInfo[]; ports?: PortInfo[] }
type TraceMeta = { duration: number; statusCount: number; bandwidthCount: number; portCount: number; peCount: number; peIds?: number[]; descriptor: TraceDescriptor | null; headerless: boolean }
type PortSeries = { read: BandwidthSample[]; write: BandwidthSample[] }
type ViewportData = { status: Segment[][]; rawStatus: Segment[][]; bandwidth: PortSeries[]; maxBandwidth: number; duration: number; start: number; end: number }
type Hover = { x: number; cycle: number; label: string; details: string; pageX: number; pageY: number }
type ExactBandwidthSample = { cycle: number; read: number[]; write: number[] }
type ViewMode = 'timeline' | 'grouped'
type TimelineRow = { kind: 'section'; label: string; h: number } | { kind: 'pe'; pe: number; h: number } | { kind: 'internal'; pe: number; h: number } | { kind: 'congestion'; pe: number; h: number } | { kind: 'port'; port: number; h: number; pe?: number }

const FALLBACK_PE_NAMES = [
  'cont0 PE 0', 'cont0 PE 1', 'cont0 PE 2', 'cont0 PE 3',
  'memReader PE 4', 'memReader PE 5', 'memReader PE 6', 'memReader PE 7',
  'reentry0 PE 8', 'reentry0 PE 9', 'reentry0 PE 10', 'reentry0 PE 11',
]
const STATE = ['Waiting', 'Active', 'Stalled', 'Finishing', 'Finishing, backpressured', 'Active, backpressured']
const COLORS = ['#e5ebf2', '#0d9f78', '#e58a1f', '#c3ccd8', '#c3ccd8', '#0d9f78']
const CONGESTION_COLORS = ['#eef2f7', '#b42318']
const HANDSHAKE_COLORS = { idle: '#e8edf4', ready: '#dbeafe', transfer: '#0d9f78', blocked: '#e58a1f' }
const READ = '#1877d2'
const WRITE = '#c43c91'
const fmtCycle = (n: number) => n >= 1e9 ? `${(n / 1e9).toFixed(2)}G` : n >= 1e6 ? `${(n / 1e6).toFixed(2)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}k` : Math.round(n).toString()
const fmtBytes = (n: number) => n >= 1e9 ? `${(n / 1e9).toFixed(2)} GB/s` : n >= 1e6 ? `${(n / 1e6).toFixed(1)} MB/s` : `${n.toFixed(0)} B/s`
const center = (sample: BandwidthSample) => (sample[0] + sample[1]) / 2
const clamp = (value: number, maximum: number) => Math.max(0, Math.min(maximum, value))
const range = (count: number) => Array.from({ length: count }, (_, index) => index)
const metaPeIds = (meta: TraceMeta | null) => meta?.peIds?.length ? meta.peIds : range(meta?.peCount || 12)
const peInfo = (pe: number, descriptor: TraceDescriptor | null) => descriptor?.pes?.find(value => value.peNumber === pe)
const fieldBitOffsets = (fields: StatusField[] = []) => {
  let offset = 0
  return fields.map(field => {
    const start = offset
    offset += field.encoding === 'readyValid2' ? 2 : 1
    return start
  })
}
const isCongestionRow = (pe: number, descriptor: TraceDescriptor | null) => {
  const info = peInfo(pe, descriptor)
  return info?.statusPrefix === 'sched_congested' || info?.kind === 'schedulerCongestion' || Boolean(info?.fields?.some(field => field.encoding === 'boolean1' && field.target?.kind === 'schedulerServer' && field.target.signal === 'congested'))
}
const isInternalRow = (pe: number, descriptor: TraceDescriptor | null) => {
  const info = peInfo(pe, descriptor)
  return Boolean(info && !isCongestionRow(pe, descriptor) && (info.task?.startsWith('internal:') || info.task?.startsWith('argumentCache:') || info.kind === 'slowUpdateEviction' || info.kind === 'argumentServer' || info.statusPrefix === 'initiator_internal' || (info.kind === 'packed' && info.fields?.some(field => field.encoding === 'readyValid2'))))
}
const shortTask = (pe?: PeInfo) => {
  if (!pe) return ''
  const prefix = pe.statusPrefix?.replace(/_status$/, '')
  if (pe.statusPrefix === 'sched_congested') return `scheduler ${shortTaskName((pe.task || '').replace(/^scheduler:/, ''))}`
  if (pe.task?.startsWith('internal:')) return `internal ${shortTaskName(pe.task.replace(/^internal:/, ''))}`
  if (pe.task?.startsWith('argumentCache:')) return `argument cache ${shortTaskName(pe.task.replace(/^argumentCache:/, ''))}`
  if (pe.task === 'memReader') return 'memReader'
  if (pe.task === 'adder' || pe.task === 'adder_unit_launcher' || pe.task === 'triangle') return shortTaskName(pe.task)
  if (pe.task?.includes('taskAdder') || pe.label?.startsWith('adder')) return 'adder'
  if (pe.task?.includes('taskInitiator') || pe.label?.startsWith('initiator')) return 'initiator'
  if (pe.task?.includes('cont0')) return 'cont0'
  if (pe.task?.includes('reentry0')) return 'reentry0'
  if (prefix) return prefix
  return pe.task || `Slot ${pe.peNumber}`
}
const shortTaskName = (task: string) => {
  if (task.includes('cont0')) return 'cont0'
  if (task === 'memReader') return 'memReader'
  if (task.includes('reentry0')) return 'reentry0'
  if (task.includes('taskAdder')) return 'adder'
  if (task.includes('taskInitiator')) return 'initiator'
  if (task === 'adder_unit_launcher') return 'adder launcher'
  return task.replaceAll('_', ' ')
}
const peTypeRank = (info?: PeInfo) => {
  const task = info?.task || ''
  const label = info?.label || ''
  if (task.includes('taskAdder') || label.startsWith('adder') || task.includes('cont0')) return 0
  if (task === 'memReader' || label.startsWith('memReader')) return 1
  if (task.includes('taskInitiator') || label.startsWith('initiator') || task.includes('reentry0')) return 2
  return 9
}
const legacyPeGroup = (task: string) => task === 'memReader' || task.includes('taskAdder') || task.includes('taskInitiator') || task.includes('cont0') || task.includes('reentry0')
const descriptorTaskOrder = (descriptor: TraceDescriptor | null) => {
  const order = new Map<string, number>()
  for (const pe of descriptor?.pes || []) {
    if (pe.kind !== 'pe') continue
    const task = pe.task || pe.label || ''
    if (task && !order.has(task)) order.set(task, order.size)
  }
  return order
}
const peSort = (descriptor: TraceDescriptor | null) => (a: number, b: number) => {
  const aa = peInfo(a, descriptor), bb = peInfo(b, descriptor)
  const order = descriptorTaskOrder(descriptor)
  const useDescriptorOrder = [...order.keys()].some(task => !legacyPeGroup(task))
  if (useDescriptorOrder) {
    const taskA = aa?.task || aa?.label || ''
    const taskB = bb?.task || bb?.label || ''
    return (order.get(taskA) ?? 999) - (order.get(taskB) ?? 999) || (aa?.indexInTask ?? a) - (bb?.indexInTask ?? b) || a - b
  }
  return peTypeRank(aa) - peTypeRank(bb) || (aa?.indexInTask ?? a) - (bb?.indexInTask ?? b) || a - b
}
const diagnosticRank = (info?: PeInfo) => {
  if (isCongestionRow(info?.peNumber ?? -1, { pes: info ? [info] : [] })) return 0
  if (info?.fields?.some(field => field.target?.kind === 'slowUpdateHandler' || field.target?.kind === 'evictionSaver') || info?.kind === 'slowUpdateEviction') return 1
  if (info?.fields?.some(field => field.target?.kind === 'argumentServer') || info?.kind === 'argumentServer') return 2
  return 9
}
const diagnosticSort = (descriptor: TraceDescriptor | null) => (a: number, b: number) => {
  const aa = peInfo(a, descriptor), bb = peInfo(b, descriptor)
  const serverA = aa?.fields?.find(field => field.target?.kind === 'argumentServer')?.target?.index ?? aa?.indexInTask ?? 0
  const serverB = bb?.fields?.find(field => field.target?.kind === 'argumentServer')?.target?.index ?? bb?.indexInTask ?? 0
  const laneA = aa?.fields?.find(field => field.target?.kind === 'argumentServer')?.target?.lane ?? 0
  const laneB = bb?.fields?.find(field => field.target?.kind === 'argumentServer')?.target?.lane ?? 0
  return diagnosticRank(aa) - diagnosticRank(bb) || serverA - serverB || laneA - laneB || a - b
}
const diagnosticRowLabel = (pe: PeInfo) => {
  const schedulerFields = pe.fields?.filter(field => field.target?.kind === 'schedulerServer') || []
  if (schedulerFields.length) {
    const targets = schedulerFields.map(field => {
      const target = field.target
      const task = shortTaskName(target?.taskName || '')
      return `${task || 'scheduler'}${target?.index === undefined ? '' : ` ${target.index}`}`
    })
    return `Scheduler Congestion${targets.length ? ` · ${targets.join(' / ')}` : ''}`
  }
  if (!pe.task?.startsWith('argumentCache:') && pe.kind !== 'slowUpdateEviction' && pe.kind !== 'argumentServer' && !pe.fields?.some(field => field.target?.kind === 'slowUpdateHandler' || field.target?.kind === 'evictionSaver' || field.target?.kind === 'argumentServer')) return `${shortTask(pe)} ${pe.indexInTask ?? 0}`
  const argumentFields = pe.fields?.filter(field => field.target?.kind === 'argumentServer') || []
  if (pe.kind === 'argumentServer' || argumentFields.length || pe.indexInTask === 1) {
    const server = argumentFields[0]?.target?.index
    const task = shortTaskName(argumentFields[0]?.target?.taskName || '')
    const lanes = argumentFields.map(field => field.target?.lane).filter((lane): lane is number => lane !== undefined)
    return `Fast Spawner${task ? ` · ${task}` : ''}${server === undefined ? '' : ` ${server}`}${lanes.length ? ` · lanes ${lanes.join('/')}` : ''}`
  }
  const slowEvictFields = pe.fields?.filter(field => field.target?.kind === 'slowUpdateHandler' || field.target?.kind === 'evictionSaver') || []
  const hasSlow = slowEvictFields.some(field => field.target?.kind === 'slowUpdateHandler')
  const hasEviction = slowEvictFields.some(field => field.target?.kind === 'evictionSaver')
  const name = hasSlow && hasEviction ? 'Slow Update / Eviction' : hasSlow ? 'Slow Update' : hasEviction ? 'Eviction' : 'Slow Update / Eviction'
  const firstTarget = slowEvictFields[0]?.target
  const task = shortTaskName(firstTarget?.taskName || '')
  const index = firstTarget?.index ?? pe.indexInTask
  return `${name}${task ? ` · ${task}` : ''}${index === undefined ? '' : ` ${index}`}`
}
const peLabel = (pe: number, descriptor: TraceDescriptor | null) => {
  const info = peInfo(pe, descriptor)
  if (!info) return FALLBACK_PE_NAMES[pe] || `PE ${pe}`
  if (isCongestionRow(pe, descriptor)) return diagnosticRowLabel(info)
  return isInternalRow(pe, descriptor) ? diagnosticRowLabel(info) : `${shortTask(info)} PE ${info.indexInTask ?? info.peNumber}`
}
const ownerLabel = (owner: string) => {
  const [base, suffix] = owner.split('#')
  const [kind, task, role] = base.split(':')
  const roleText = suffix || role || ''
  if (kind === 'pe') return `${shortTaskName(task)} ${roleText}`.trim()
  if (kind === 'scheduler') return `scheduler ${roleText} · ${shortTaskName(task)}`.trim()
  if (kind === 'argumentNotifier' || kind === 'newArgumentNotifier') return `argumentNotifier · ${shortTaskName(task)}`
  if (kind === 'closureAllocator') return `closureAllocator · ${shortTaskName(task)}`
  return owner
}
const portLabel = (port: number, descriptor: TraceDescriptor | null, peLabels: string[]) => {
  const info = descriptor?.ports?.find(value => value.port === port)
  if (!info) return `Port ${String(port).padStart(2, '0')}`
  const peOwners = [...new Set(info.masters.map(master => master.peNumber).filter((pe): pe is number => pe !== null))]
  const owners = peOwners.length ? peOwners.map(pe => peLabels[pe] || `PE ${pe}`).join(', ') : info.masters.map(master => ownerLabel(master.owner)).join(', ')
  return `${owners} · ${info.portName || `port ${port}`}`
}
const portOwners = (port: number, descriptor: TraceDescriptor | null) => descriptor?.ports?.find(value => value.port === port)?.masters.map(master => `${ownerLabel(master.owner)}${master.role ? ` #${master.role}` : ''}${master.peNumber === null ? '' : ` → PE ${master.peNumber}`}`).join(' · ') || ''
const portSortKey = (port: number, descriptor: TraceDescriptor | null) => {
  const info = descriptor?.ports?.find(value => value.port === port)
  if (!info) return [0, port, 0, port]
  const peOwners = info.masters.map(master => master.peNumber).filter((pe): pe is number => pe !== null)
  if (peOwners.length) return [0, Math.min(...peOwners), Math.min(...info.masters.map(master => master.wId ?? 99)), port]
  const owner = info.masters[0]?.owner || ''
  const infraRank = owner.startsWith('scheduler:') ? 1 : owner.startsWith('argumentNotifier:') || owner.startsWith('newArgumentNotifier:') ? 2 : owner.startsWith('closureAllocator:') ? 3 : 4
  return [1, infraRank, owner, port]
}
const comparePorts = (descriptor: TraceDescriptor | null) => (a: number, b: number) => {
  const aa = portSortKey(a, descriptor), bb = portSortKey(b, descriptor)
  for (let i = 0; i < Math.min(aa.length, bb.length); i++) if (aa[i] !== bb[i]) return aa[i] < bb[i] ? -1 : 1
  return a - b
}
const splineControls = (samples: BandwidthSample[], index: number, maximum: number) => {
  const previous = samples[Math.max(0, index - 1)][2]
  const current = samples[index][2]
  const next = samples[index + 1][2]
  const following = samples[Math.min(samples.length - 1, index + 2)][2]
  return [clamp(current + (next - previous) / 6, maximum), clamp(next - (following - current) / 6, maximum)] as const
}
const handshakeText = (name: string, nibble: number, validBit: number, readyBit: number) => {
  const valid = Boolean(nibble & validBit)
  const ready = Boolean(nibble & readyBit)
  if (valid && ready) return `${name}: transfer`
  if (valid) return `${name}: valid, backpressured`
  if (ready) return `${name}: ready, no valid`
  return `${name}: idle`
}
const diagnosticNames = (info?: PeInfo) => {
  if (info?.fields?.length) {
    return info.fields.filter(field => field.encoding === 'readyValid2').map(field => {
      const target = field.target
      if (target?.kind === 'slowUpdateHandler') return 'slowUpdateOut'
      if (target?.kind === 'evictionSaver') return 'evictionOut'
      if (target?.kind === 'argumentServer') return `fastSpawnLane${target.lane ?? ''}`
      return target?.port || target?.signal || 'signal'
    }).slice(0, 2)
  }
  if (info?.task?.startsWith('argumentCache:')) {
    return info.indexInTask === 0 ? ['slowUpdateOut', 'evictionOut'] : ['fastSpawnLane0', 'fastSpawnLane1']
  }
  if (info?.kind === 'slowUpdateEviction') return ['slowUpdateOut', 'evictionOut']
  if (info?.kind === 'argumentServer') return ['fastSpawnLane0', 'fastSpawnLane1']
  return ['closureIn', 'raw spawnNext']
}
const readyValidFields = (info?: PeInfo) => {
  const offsets = fieldBitOffsets(info?.fields)
  const fields = info?.fields?.map((field, index) => ({ field, offset: offsets[index] })).filter(item => item.field.encoding === 'readyValid2') || []
  if (fields.length) return fields
  if (info?.kind === 'slowUpdateEviction' || info?.kind === 'argumentServer') return [0, 2].map(offset => ({ field: { encoding: 'readyValid2' as const, target: undefined }, offset }))
  return []
}
const booleanFields = (info?: PeInfo) => {
  const offsets = fieldBitOffsets(info?.fields)
  const fields = info?.fields?.map((field, index) => ({ field, offset: offsets[index] })).filter(item => item.field.encoding === 'boolean1') || []
  if (fields.length) return fields
  if (info?.kind === 'schedulerCongestion' || info?.label === 'schedulerCongestion') return [0, 1, 2, 3].map(offset => ({ field: { encoding: 'boolean1' as const, target: undefined }, offset }))
  return []
}
const internalDetails = (nibble: number, info?: PeInfo) => {
  const [first, second] = diagnosticNames(info)
  const fields = readyValidFields(info)
  if (fields.length) return fields.map(({ field, offset }, index) => handshakeText(diagnosticNames(info)[index] || field.target?.port || 'signal', nibble, 1 << offset, 1 << (offset + 1))).join(' · ')
  return `${handshakeText(first, nibble, 1, 2)} · ${handshakeText(second, nibble, 4, 8)}`
}

function useWidth(ref: RefObject<HTMLElement | null>) {
  const [width, setWidth] = useState(800)
  useEffect(() => {
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    if (ref.current) observer.observe(ref.current)
    return () => observer.disconnect()
  }, [ref])
  return width
}

type TimelineProps = {
  traceId: string
  data: ViewportData
  view: Range
  setView: (range: Range) => void
  visiblePEs: Set<number>
  visiblePorts: Set<number>
  expanded: Set<number>
  mode: ViewMode
  descriptor: TraceDescriptor | null
  peLabels: string[]
  onExpand: (port: number) => void
}

function Timeline({ traceId, data, view, setView, visiblePEs, visiblePorts, expanded, mode, descriptor, peLabels, onExpand }: TimelineProps) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const dragRef = useRef<{ x: number; view: Range; pointerId: number } | null>(null)
  const viewFrame = useRef<number | null>(null)
  const queuedView = useRef<Range | null>(null)
  const sampleCache = useRef(new Map<string, ExactBandwidthSample>())
  const pendingSamples = useRef(new Set<string>())
  const [hover, setHover] = useState<Hover | null>(null)
  const [dragging, setDragging] = useState(false)
  const width = useWidth(wrapRef)
  const pes = useMemo(() => [...visiblePEs].filter(pe => !isCongestionRow(pe, descriptor) && !isInternalRow(pe, descriptor)).sort(peSort(descriptor)), [visiblePEs, descriptor])
  const congestionRows = useMemo(() => [...visiblePEs].filter(pe => isCongestionRow(pe, descriptor)).sort(diagnosticSort(descriptor)), [visiblePEs, descriptor])
  const internalRows = useMemo(() => [...visiblePEs].filter(pe => isInternalRow(pe, descriptor)).sort(diagnosticSort(descriptor)), [visiblePEs, descriptor])
  const ports = useMemo(() => [...visiblePorts].sort((a, b) => a - b), [visiblePorts])
  const rowH = 36
  const sectionH = 34
  const plotLeft = Math.min(240, Math.max(190, width * 0.18))
  const plotRight = 24
  const plotW = Math.max(1, width - plotLeft - plotRight)
  const rows = useMemo<TimelineRow[]>(() => {
    const orderedPorts = [...ports].sort(comparePorts(descriptor))
    const portToPes = new Map<number, number[]>()
    for (const port of orderedPorts) {
      const info = descriptor?.ports?.find(value => value.port === port)
      const owners = info?.masters.map(master => master.peNumber).filter((pe): pe is number => pe !== null) || []
      portToPes.set(port, [...new Set(owners)])
    }
    const portH = (port: number) => expanded.has(port) ? 94 : 52
    if (mode === 'timeline') return [
      { kind: 'section', label: 'PROCESSING ELEMENTS', h: sectionH },
      ...pes.map(pe => ({ kind: 'pe' as const, pe, h: rowH })),
      ...(internalRows.length ? [{ kind: 'section' as const, label: 'INTERNAL SIGNALS', h: sectionH }, ...internalRows.map(pe => ({ kind: 'internal' as const, pe, h: rowH }))] : []),
      ...(congestionRows.length ? [{ kind: 'section' as const, label: 'SCHEDULER CONGESTION', h: sectionH }, ...congestionRows.map(pe => ({ kind: 'congestion' as const, pe, h: rowH }))] : []),
      { kind: 'section', label: 'MEMORY PORTS', h: sectionH + 8 },
      ...orderedPorts.map(port => ({ kind: 'port' as const, port, h: portH(port) })),
    ]
    const result: TimelineRow[] = [{ kind: 'section', label: 'GROUPED BY MEMORY PORT', h: sectionH }]
    const usedPorts = new Set<number>()
    for (const pe of pes) {
      result.push({ kind: 'pe', pe, h: rowH })
      for (const port of orderedPorts) {
        if (portToPes.get(port)?.includes(pe)) {
          result.push({ kind: 'port', port, pe, h: portH(port) })
          usedPorts.add(port)
        }
      }
    }
    if (internalRows.length) {
      result.push({ kind: 'section', label: 'INTERNAL SIGNALS', h: sectionH })
      for (const pe of internalRows) result.push({ kind: 'internal', pe, h: rowH })
    }
    if (congestionRows.length) {
      result.push({ kind: 'section', label: 'SCHEDULER CONGESTION', h: sectionH })
      for (const pe of congestionRows) result.push({ kind: 'congestion', pe, h: rowH })
    }
    const shared = orderedPorts.filter(port => !usedPorts.has(port))
    if (shared.length) result.push({ kind: 'section', label: 'SHARED / UNMAPPED MEMORY PORTS', h: sectionH })
    for (const port of shared) result.push({ kind: 'port', port, h: portH(port) })
    return result
  }, [descriptor, expanded, mode, pes, congestionRows, internalRows, ports])
  const height = rows.reduce((sum, row) => sum + row.h, 0) + 48
  const span = view[1] - view[0]
  const pixelsPerCycle = plotW / Math.max(1, span)
  const showCycleGuides = pixelsPerCycle >= 8
  const cycleStep = (minimum: number) => {
    const base = [1, 2, 4, 8, 16, 32, 64, 128]
    for (const step of base) if (step >= minimum) return step
    return 128 * 2 ** Math.ceil(Math.log2(minimum / 128))
  }
  const labelTickStep = cycleStep(92 / Math.max(.001, pixelsPerCycle))
  const gridTickStep = showCycleGuides ? 1 : labelTickStep
  const firstLabelTick = Math.ceil(view[0] / labelTickStep) * labelTickStep
  const labelTicks = useMemo(() => { const values: number[] = []; for (let cycle = firstLabelTick; cycle <= view[1]; cycle += labelTickStep) values.push(cycle); return values }, [firstLabelTick, labelTickStep, view])
  const firstGridTick = Math.ceil(view[0] / gridTickStep) * gridTickStep
  const gridTicks = useMemo(() => {
    if (gridTickStep >= labelTickStep) return []
    const values: number[] = []
    for (let cycle = firstGridTick; cycle <= view[1]; cycle += gridTickStep) if (cycle % labelTickStep !== 0) values.push(cycle)
    return values
  }, [firstGridTick, gridTickStep, labelTickStep, view])
  const scheduleView = (range: Range) => {
    queuedView.current = range
    if (viewFrame.current !== null) return
    viewFrame.current = requestAnimationFrame(() => {
      viewFrame.current = null
      const next = queuedView.current
      queuedView.current = null
      if (next) setView(next)
    })
  }

  useEffect(() => () => {
    if (viewFrame.current !== null) cancelAnimationFrame(viewFrame.current)
  }, [])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const dpr = devicePixelRatio || 1
    canvas.width = width * dpr
    canvas.height = height * dpr
    canvas.style.width = `${width}px`
    canvas.style.height = `${height}px`
    const c = canvas.getContext('2d')
    if (!c) return
    c.scale(dpr, dpr)
    const x = (cycle: number) => plotLeft + ((cycle - view[0]) / (view[1] - view[0])) * plotW
    const textFit = (text: string, maxWidth: number) => {
      if (maxWidth <= 12 || c.measureText(text).width <= maxWidth) return text
      let low = 0, high = text.length
      while (low < high) {
        const middle = Math.ceil((low + high) / 2)
        if (c.measureText(`${text.slice(0, middle)}…`).width <= maxWidth) low = middle
        else high = middle - 1
      }
      return `${text.slice(0, low)}…`
    }
    c.fillStyle = '#ffffff'
    c.fillRect(0, 0, width, height)
    c.font = '13px Inter, system-ui, sans-serif'
    c.textBaseline = 'middle'
    const fillStatus = (left: number, top: number, segmentW: number, segmentH: number, state: number) => {
      c.fillStyle = COLORS[state] || COLORS[0]
      c.fillRect(left, top, segmentW, segmentH)
      if (state !== 4 && state !== 5) return
      c.save()
      c.beginPath(); c.rect(left, top, segmentW, segmentH); c.clip()
      c.strokeStyle = '#e58a1f'
      c.lineWidth = 2
      for (let stripe = left - segmentH; stripe < left + segmentW + segmentH; stripe += 8) {
        c.beginPath(); c.moveTo(stripe, top + segmentH); c.lineTo(stripe + segmentH, top); c.stroke()
      }
      c.restore()
    }
    const fillHandshake = (left: number, top: number, segmentW: number, segmentH: number, nibble: number, validBit: number, readyBit: number) => {
      const valid = Boolean(nibble & validBit)
      const ready = Boolean(nibble & readyBit)
      c.fillStyle = valid && ready ? HANDSHAKE_COLORS.transfer : valid ? HANDSHAKE_COLORS.blocked : ready ? HANDSHAKE_COLORS.ready : HANDSHAKE_COLORS.idle
      c.fillRect(left, top, segmentW, segmentH)
      if (!valid || ready) return
      c.save()
      c.beginPath(); c.rect(left, top, segmentW, segmentH); c.clip()
      c.strokeStyle = '#9a5c12'
      c.lineWidth = 1.5
      for (let stripe = left - segmentH; stripe < left + segmentW + segmentH; stripe += 7) {
        c.beginPath(); c.moveTo(stripe, top + segmentH); c.lineTo(stripe + segmentH, top); c.stroke()
      }
      c.restore()
    }
    const max = Math.max(1, data.maxBandwidth)
    const drawLine = (port: number, key: keyof PortSeries, color: string, top: number, laneH: number) => {
      const vals = data.bandwidth[port]?.[key] || []
      if (!vals.length) return
      const pointY = (value: number) => top + laneH - 7 - value / max * (laneH - 14)
      c.save()
      c.beginPath(); c.rect(plotLeft, top + 3, plotW, laneH - 6); c.clip()
      c.strokeStyle = color
      c.lineWidth = 3
      c.lineCap = 'round'
      c.lineJoin = 'round'
      c.beginPath()
      c.moveTo(x(vals[0][0]), pointY(vals[0][2]))
      c.lineTo(x(center(vals[0])), pointY(vals[0][2]))
      for (let index = 0; index < vals.length - 1; index++) {
        const currentCenter = center(vals[index]), nextCenter = center(vals[index + 1])
        const interval = nextCenter - currentCenter
        const [controlA, controlB] = splineControls(vals, index, max)
        c.bezierCurveTo(
          x(currentCenter + interval / 3), pointY(controlA),
          x(nextCenter - interval / 3), pointY(controlB),
          x(nextCenter), pointY(vals[index + 1][2]),
        )
      }
      c.lineTo(x(vals.at(-1)![1]), pointY(vals.at(-1)![2]))
      c.stroke()
      c.restore()
    }

    c.save()
    c.beginPath()
    c.rect(plotLeft, 0, plotW, height - 32)
    c.clip()
    for (const cycle of labelTicks) {
      const xx = x(cycle)
      c.strokeStyle = '#eef2f7'
      c.lineWidth = 1
      c.beginPath(); c.moveTo(xx, 30); c.lineTo(xx, height - 34); c.stroke()
    }
    c.restore()
    const drawCycleGuides = () => {
      if (!showCycleGuides) return
      c.save()
      c.beginPath()
      c.rect(plotLeft, 30, plotW, height - 64)
      c.clip()
      for (const cycle of gridTicks) {
        const xx = x(cycle)
        c.strokeStyle = gridTickStep === 1 ? '#ffffffcc' : '#ffffff99'
        c.lineWidth = gridTickStep === 1 ? 1.15 : 1
        c.beginPath(); c.moveTo(xx, 30); c.lineTo(xx, height - 34); c.stroke()
      }
      for (const cycle of labelTicks) {
        const xx = x(cycle)
        c.strokeStyle = '#d2dbe7a6'
        c.lineWidth = 1
        c.beginPath(); c.moveTo(xx, 30); c.lineTo(xx, height - 34); c.stroke()
      }
      c.restore()
    }

    let y = 0
    for (const row of rows) {
      if (row.kind === 'section') {
        c.fillStyle = '#526174'
        c.font = '13px Inter, system-ui, sans-serif'
        c.textAlign = 'left'
        c.fillText(row.label, 16, y + row.h / 2)
      } else if (row.kind === 'pe') {
        c.fillStyle = mode === 'grouped' ? '#eef3f8' : '#ffffff'
        if (mode === 'grouped') c.fillRect(8, y + 2, width - 16, row.h - 4)
        c.fillStyle = '#344256'
        c.font = '13px Inter, system-ui, sans-serif'
        c.textAlign = 'left'
        c.fillText(textFit(peLabels[row.pe] || `PE ${row.pe}`, plotLeft - 28), 16, y + row.h / 2)
        c.fillStyle = '#f4f7fa'
        c.fillRect(plotLeft, y + 4, plotW, row.h - 8)
        c.save()
        c.beginPath(); c.rect(plotLeft, y + 4, plotW, row.h - 8); c.clip()
        for (const [start, end, state] of data.status[row.pe] || []) {
          const left = x(start)
          fillStatus(left, y + 5, Math.max(1, x(end) - left), row.h - 10, state)
        }
        c.restore()
      } else if (row.kind === 'internal') {
        const info = peInfo(row.pe, descriptor)
        const rvFields = readyValidFields(info)
        c.fillStyle = '#f8fafc'
        c.fillRect(8, y + 2, width - 16, row.h - 4)
        c.fillStyle = '#344256'
        c.font = '13px Inter, system-ui, sans-serif'
        c.textAlign = 'left'
        c.fillText(textFit(peLabels[row.pe] || `Internal ${row.pe}`, plotLeft - 28), 16, y + row.h / 2)
        const laneH = Math.floor((row.h - 12) / 2)
        c.fillStyle = '#f4f7fa'
        c.fillRect(plotLeft, y + 4, plotW, laneH)
        c.fillRect(plotLeft, y + 8 + laneH, plotW, laneH)
        c.strokeStyle = '#d7e0ea'
        c.lineWidth = 1
        c.beginPath(); c.moveTo(plotLeft, y + 6 + laneH); c.lineTo(width - plotRight, y + 6 + laneH); c.stroke()
        c.save()
        c.beginPath(); c.rect(plotLeft, y + 4, plotW, row.h - 8); c.clip()
        for (const [start, end, nibble] of data.rawStatus[row.pe] || data.status[row.pe] || []) {
          const left = x(start)
          const segmentW = Math.max(1, x(end) - left)
          if (rvFields.length) {
            fillHandshake(left, y + 4, segmentW, laneH, nibble, 1 << rvFields[0].offset, 1 << (rvFields[0].offset + 1))
            if (rvFields[1]) fillHandshake(left, y + 8 + laneH, segmentW, laneH, nibble, 1 << rvFields[1].offset, 1 << (rvFields[1].offset + 1))
          } else {
            fillHandshake(left, y + 4, segmentW, laneH, nibble, 1, 2)
            fillHandshake(left, y + 8 + laneH, segmentW, laneH, nibble, 4, 8)
          }
        }
        c.restore()
      } else if (row.kind === 'congestion') {
        const info = peInfo(row.pe, descriptor)
        const bools = booleanFields(info)
        c.fillStyle = '#fff7ed'
        c.fillRect(8, y + 2, width - 16, row.h - 4)
        c.fillStyle = '#7c2d12'
        c.font = '13px Inter, system-ui, sans-serif'
        c.textAlign = 'left'
        c.fillText(textFit(peLabels[row.pe] || `Scheduler ${row.pe}`, plotLeft - 28), 16, y + row.h / 2)
        const laneCount = Math.max(1, bools.length)
        const signalH = bools.length > 1 ? Math.max(4, Math.floor((row.h - 12) / laneCount)) : 10
        const blockH = signalH * laneCount + Math.max(0, laneCount - 1) * 2
        const signalTop = y + Math.round((row.h - blockH) / 2)
        c.fillStyle = '#f8fafc'
        c.fillRect(plotLeft, signalTop, plotW, blockH)
        c.save()
        c.beginPath(); c.rect(plotLeft, signalTop, plotW, blockH); c.clip()
        for (const [start, end, value] of data.rawStatus[row.pe] || data.status[row.pe] || []) {
          const left = x(start)
          const segmentW = Math.max(1, x(end) - left)
          if (bools.length) {
            for (const [index, field] of bools.entries()) {
              c.fillStyle = CONGESTION_COLORS[value & (1 << field.offset) ? 1 : 0]
              c.fillRect(left, signalTop + index * (signalH + 2), segmentW, signalH)
            }
          } else {
            c.fillStyle = CONGESTION_COLORS[value === 1 ? 1 : 0]
            c.fillRect(left, signalTop, segmentW, signalH)
          }
        }
        c.restore()
      } else {
        const expandedRow = expanded.has(row.port)
        const label = portLabel(row.port, descriptor, peLabels)
        const owners = portOwners(row.port, descriptor)
        const labelLeft = mode === 'grouped' && row.pe !== undefined ? 30 : 16
        if (expandedRow) {
          c.fillStyle = '#f8fafc'; c.strokeStyle = '#b8c6d6'; c.lineWidth = 1
          c.beginPath(); c.roundRect(labelLeft - 8, y + 2, width - labelLeft, row.h - 5, 8); c.fill(); c.stroke()
          c.fillStyle = '#26374c'; c.font = '600 13px Inter, system-ui, sans-serif'; c.fillText(textFit(label, plotLeft - labelLeft - 12), labelLeft, y + 18)
          c.font = '12px Inter, system-ui, sans-serif'
          c.fillStyle = READ; c.beginPath(); c.arc(labelLeft + 4, y + 43, 4, 0, Math.PI * 2); c.fill(); c.fillText('READ', labelLeft + 14, y + 43)
          c.fillStyle = WRITE; c.beginPath(); c.arc(labelLeft + 4, y + 70, 4, 0, Math.PI * 2); c.fill(); c.fillText('WRITE', labelLeft + 14, y + 70)
          c.fillStyle = '#6c7c91'; c.fillText('Collapse', labelLeft + 70, y + 43)
          c.fillStyle = '#f1f6fb'; c.fillRect(plotLeft, y + 6, plotW, row.h / 2 - 7)
          c.fillStyle = '#fbf3f8'; c.fillRect(plotLeft, y + row.h / 2 + 1, plotW, row.h / 2 - 7)
          c.strokeStyle = '#cbd6e2'; c.beginPath(); c.moveTo(plotLeft, y + row.h / 2); c.lineTo(width - plotRight, y + row.h / 2); c.stroke()
        } else {
          c.fillStyle = '#344256'; c.font = '13px Inter, system-ui, sans-serif'; c.fillText(textFit(label, plotLeft - labelLeft - 12), labelLeft, y + row.h / 2 - 7)
          c.fillStyle = '#6c7c91'; c.font = '12px Inter, system-ui, sans-serif'; c.fillText(textFit(owners || 'Expand', plotLeft - labelLeft - 12), labelLeft, y + row.h / 2 + 10)
          c.fillStyle = '#f4f7fa'; c.fillRect(plotLeft, y + 3, plotW, row.h - 6)
        }
        c.font = '13px Inter, system-ui, sans-serif'
        if (expandedRow) {
          drawLine(row.port, 'read', READ, y, row.h / 2)
          drawLine(row.port, 'write', WRITE, y + row.h / 2, row.h / 2)
        } else {
          drawLine(row.port, 'read', READ, y, row.h)
          drawLine(row.port, 'write', WRITE, y, row.h)
        }
      }
      y += row.h
    }
    drawCycleGuides()

  }, [data, view, width, height, rows, expanded, plotLeft, plotW, labelTicks, gridTicks, gridTickStep, showCycleGuides, descriptor, peLabels, mode])

  const pointer = (event: PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current
    if (!canvas) return
    const rect = canvas.getBoundingClientRect()
    const pointerX = event.clientX - rect.left
    if (dragRef.current) {
      const drag = dragRef.current
      const dragSpan = drag.view[1] - drag.view[0]
      const delta = (event.clientX - drag.x) / plotW * dragSpan
      const start = Math.max(0, Math.min(data.duration - dragSpan, drag.view[0] - delta))
      scheduleView([start, start + dragSpan])
    }
    if (pointerX < plotLeft || pointerX > width - plotRight) return setHover(null)
    const cycle = view[0] + (pointerX - plotLeft) / plotW * (view[1] - view[0])
    const pointerY = event.clientY - rect.top
    let rowY = 0, label = '', details = ''
    for (const row of rows) {
      if (pointerY >= rowY && pointerY < rowY + row.h && row.kind === 'pe') {
        const segment = (data.status[row.pe] || []).find(value => cycle >= value[0] && cycle <= value[1])
        label = peLabels[row.pe] || `PE ${row.pe}`; details = segment ? STATE[segment[2]] : 'No sample'
        setHover({ x: pointerX, cycle, label, details, pageX: pointerX, pageY: pointerY })
        return
      }
      if (pointerY >= rowY && pointerY < rowY + row.h && row.kind === 'internal') {
        const segment = (data.rawStatus[row.pe] || data.status[row.pe] || []).find(value => cycle >= value[0] && cycle <= value[1])
        label = peLabels[row.pe] || `Internal ${row.pe}`; details = segment ? internalDetails(segment[2], peInfo(row.pe, descriptor)) : 'No sample'
        setHover({ x: pointerX, cycle, label, details, pageX: pointerX, pageY: pointerY })
        return
      }
      if (pointerY >= rowY && pointerY < rowY + row.h && row.kind === 'congestion') {
        const info = peInfo(row.pe, descriptor)
        const segment = (data.rawStatus[row.pe] || data.status[row.pe] || []).find(value => cycle >= value[0] && cycle <= value[1])
        const bools = booleanFields(info)
        label = peLabels[row.pe] || `Scheduler ${row.pe}`
        details = segment && bools.length ? bools.map(({ field, offset }) => `${shortTaskName(field.target?.taskName || '')}${field.target?.index !== undefined ? ` ${field.target.index}` : ''}: ${segment[2] & (1 << offset) ? 'congested' : 'clear'}`).join(' · ') : segment?.[2] === 1 ? 'Network congested' : 'Not congested'
        setHover({ x: pointerX, cycle, label, details, pageX: pointerX, pageY: pointerY })
        return
      }
      if (pointerY >= rowY && pointerY < rowY + row.h && row.kind === 'port') {
        const samples = data.bandwidth[row.port]?.read || []
        const nearest = samples.reduce<BandwidthSample | null>((best, sample) => !best || Math.abs(center(sample) - cycle) < Math.abs(center(best) - cycle) ? sample : best, null)
        const snappedCycle = nearest ? center(nearest) : Math.max(64, Math.min(data.duration - 64, Math.round((cycle - 64) / 128) * 128 + 64))
        const snappedX = plotLeft + ((snappedCycle - view[0]) / (view[1] - view[0])) * plotW
        const lane = expanded.has(row.port) ? (pointerY < rowY + row.h / 2 ? 'Read' : 'Write') : ''
        label = `${portLabel(row.port, descriptor, peLabels)}${lane ? ` · ${lane}` : ''}`
        const cacheKey = `${traceId}:${snappedCycle}`
        const cached = sampleCache.current.get(cacheKey)
        details = cached ? `Read ${fmtBytes((cached.read[row.port] || 0) * 1e8)} · Write ${fmtBytes((cached.write[row.port] || 0) * 1e8)}` : 'Loading exact 128-cycle average…'
        setHover({ x: snappedX, cycle: snappedCycle, label, details, pageX: snappedX, pageY: pointerY })
        if (!cached && !pendingSamples.current.has(cacheKey)) {
          pendingSamples.current.add(cacheKey)
          fetch(`/api/traces/${encodeURIComponent(traceId)}/sample?cycle=${snappedCycle}`).then(response => response.json()).then((sample: ExactBandwidthSample) => {
          sampleCache.current.set(cacheKey, sample)
          setHover(current => current?.cycle === snappedCycle && current.label === label ? { ...current, details: `Read ${fmtBytes((sample.read[row.port] || 0) * 1e8)} · Write ${fmtBytes((sample.write[row.port] || 0) * 1e8)}` } : current)
          }).finally(() => pendingSamples.current.delete(cacheKey))
        }
        return
      }
      rowY += row.h
    }
    setHover({ x: pointerX, cycle, label, details, pageX: pointerX, pageY: pointerY })
  }
  const pointerDown = (event: PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current
    if (!canvas || event.button !== 0) return
    const pointerX = event.clientX - canvas.getBoundingClientRect().left
    if (pointerX < plotLeft || pointerX > width - plotRight) return
    dragRef.current = { x: event.clientX, view, pointerId: event.pointerId }
    canvas.setPointerCapture(event.pointerId)
    setDragging(true)
  }
  const pointerUp = (event: PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current
    if (canvas?.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId)
    if (queuedView.current) {
      setView(queuedView.current)
      queuedView.current = null
    }
    dragRef.current = null
    setDragging(false)
  }
  const click = (event: PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current
    if (!canvas || event.clientX - canvas.getBoundingClientRect().left > plotLeft) return
    let y = 0
    const pointerY = event.clientY - canvas.getBoundingClientRect().top
    for (const row of rows) {
      if (row.kind === 'port' && pointerY >= y && pointerY < y + row.h) onExpand(row.port)
      y += row.h
    }
  }
  return <div className="timeline-wrap" ref={wrapRef}>
    <canvas className={dragging ? 'dragging' : ''} ref={canvasRef} onPointerDown={pointerDown} onPointerMove={pointer} onPointerUp={pointerUp} onPointerCancel={pointerUp} onPointerLeave={() => { if (!dragRef.current) setHover(null) }} onClick={click} />
    {hover && <div className="hover-guide" style={{ left: hover.x }} />}
    {hover && <div className="tooltip" style={{ left: Math.min(width - 280, hover.pageX + 16), top: hover.pageY + 16 }}><strong>{hover.label || 'Timeline'}</strong><span>{hover.details}</span><small>Cycle {Math.floor(hover.cycle).toLocaleString()}</small></div>}
    <div className="sticky-axis">
      <span className="axis-title">CYCLES · labels {labelTickStep.toLocaleString()}{showCycleGuides ? ' · cycle guides' : ''}</span>
      <div className="axis-scale" style={{ left: plotLeft, right: plotRight }}>
        {gridTicks.map(cycle => <span className="minor" key={`g-${cycle}`} style={{ left: `${(cycle - view[0]) / span * 100}%` }} />)}
        {labelTicks.map(cycle => <span key={cycle} style={{ left: `${(cycle - view[0]) / span * 100}%` }}>{cycle.toLocaleString()}</span>)}
      </div>
    </div>
  </div>
}

type ChecksProps = { title: string; items: number[]; selected: Set<number>; setSelected: (value: Set<number>) => void; label: (item: number) => string }
function Checks({ title, items, selected, setSelected, label }: ChecksProps) {
  const all = selected.size === items.length
  return <section className="filter"><header><span>{title}</span><button onClick={() => setSelected(all ? new Set() : new Set(items))}>{all ? 'Hide all' : 'Show all'}</button></header>
    <div className="checks">{items.map(item => <label key={item}><input type="checkbox" checked={selected.has(item)} onChange={() => { const next = new Set(selected); next.has(item) ? next.delete(item) : next.add(item); setSelected(next) }} /><span>{label(item)}</span></label>)}</div>
  </section>
}

type RangeControlsProps = { view: Range; duration: number; setView: (range: Range) => void }
function RangeControls({ view, duration, setView }: RangeControlsProps) {
  const span = view[1] - view[0]
  const clamp = (start: number, end: number): Range => {
    start = Math.max(0, Math.min(duration - 1, start))
    end = Math.max(start + 1, Math.min(duration, end))
    return [start, end]
  }
  const pan = (amount: number) => { const start = Math.max(0, Math.min(duration - span, view[0] + span * amount)); setView([start, start + span]) }
  const zoom = (factor: number) => { const nextSpan = Math.max(4, Math.min(duration, span * factor)); const center = (view[0] + view[1]) / 2; const start = Math.max(0, Math.min(duration - nextSpan, center - nextSpan / 2)); setView([start, start + nextSpan]) }
  useEffect(() => {
    const keyDown = (event: KeyboardEvent) => {
      const target = event.target
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement || (target instanceof HTMLElement && target.isContentEditable)) return
      if (event.key === '-' || event.code === 'NumpadSubtract') { event.preventDefault(); zoom(2) }
      else if (event.key === '=' || event.code === 'NumpadAdd') { event.preventDefault(); zoom(.5) }
      else if (event.key === 'ArrowLeft') { event.preventDefault(); pan(-.2) }
      else if (event.key === 'ArrowRight') { event.preventDefault(); pan(.2) }
    }
    window.addEventListener('keydown', keyDown)
    return () => window.removeEventListener('keydown', keyDown)
  })
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setView(clamp(Number(form.get('start')), Number(form.get('end'))))
  }
  return <div className="range-panel">
    <form key={`${Math.round(view[0])}-${Math.round(view[1])}`} onSubmit={submit}>
      <label>START CYCLE<input name="start" type="number" min="0" max={duration - 1} defaultValue={Math.round(view[0])} /></label>
      <span>to</span>
      <label>END CYCLE<input name="end" type="number" min="1" max={duration} defaultValue={Math.round(view[1])} /></label>
      <button type="submit">Apply range</button>
    </form>
    <div className="time-controls">
      <button onClick={() => pan(-.8)} aria-label="Pan left">←</button>
      <button onClick={() => zoom(2)} aria-label="Zoom out">−</button>
      <input aria-label="Horizontal timeline position" type="range" min="0" max={Math.max(0, duration - span)} step={Math.max(1, span / 300)} value={view[0]} disabled={span >= duration} onChange={event => { const start = Number(event.target.value); setView([start, start + span]) }} />
      <button onClick={() => zoom(.5)} aria-label="Zoom in">+</button>
      <button onClick={() => pan(.8)} aria-label="Pan right">→</button>
      <button className="fit" onClick={() => setView([0, duration])}>Fit trace</button>
    </div>
    <div className="shortcut-hint"><kbd>−</kbd><kbd>=</kbd> zoom <span>·</span> <kbd>←</kbd><kbd>→</kbd> pan</div>
  </div>
}

export default function App() {
  const [traces, setTraces] = useState<TraceRow[]>([])
  const [selected, setSelected] = useState('')
  const [meta, setMeta] = useState<TraceMeta | null>(null)
  const [data, setData] = useState<ViewportData | null>(null)
  const [view, setView] = useState<Range>([0, 1])
  const [loading, setLoading] = useState(false)
  const [pes, setPes] = useState(new Set([...Array(12).keys()]))
  const [ports, setPorts] = useState(new Set([...Array(28).keys()]))
  const [expanded, setExpanded] = useState(new Set<number>())
  const [viewMode, setViewMode] = useState<ViewMode>('timeline')
  const refresh = useCallback(async () => { const rows: TraceRow[] = await fetch('/api/traces').then(response => response.json()); setTraces(rows); setSelected(current => rows.some(row => row.id === current) ? current : rows[0]?.id || '') }, [])
  useEffect(() => { queueMicrotask(refresh); const events = new EventSource('/api/events'); events.onmessage = refresh; return () => events.close() }, [refresh])
  useEffect(() => { if (!selected) return; fetch(`/api/traces/${encodeURIComponent(selected)}/meta`).then(response => response.json()).then((next: TraceMeta) => { setMeta(next); setView([0, Math.max(1, next.duration)]); setPes(new Set(metaPeIds(next))); setPorts(new Set(range(next.portCount))); setLoading(false) }).catch(() => setLoading(false)) }, [selected])
  useEffect(() => {
    if (!meta) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      const points = Math.min(1800, Math.max(500, Math.floor(innerWidth)))
      const query = new URLSearchParams({ start: String(view[0]), end: String(view[1]), points: String(points) })
      fetch(`/api/traces/${encodeURIComponent(selected)}/data?${query}`, { signal: controller.signal }).then(response => response.json()).then((next: ViewportData) => setData(next)).catch(error => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) console.error(error)
      })
    }, 140)
    return () => { clearTimeout(timer); controller.abort() }
  }, [selected, meta, view])
  const trace = traces.find(row => row.id === selected)
  const descriptor = meta?.descriptor || null
  const allPeItems = useMemo(() => metaPeIds(meta), [meta])
  const peItems = useMemo(() => allPeItems.filter(pe => !isCongestionRow(pe, descriptor) && !isInternalRow(pe, descriptor)).sort(peSort(descriptor)), [allPeItems, descriptor])
  const congestionItems = useMemo(() => allPeItems.filter(pe => isCongestionRow(pe, descriptor)).sort(diagnosticSort(descriptor)), [allPeItems, descriptor])
  const internalItems = useMemo(() => allPeItems.filter(pe => isInternalRow(pe, descriptor)).sort(diagnosticSort(descriptor)), [allPeItems, descriptor])
  const peLabels = useMemo(() => {
    const maxPe = allPeItems.length ? Math.max(...allPeItems) : (meta?.peCount || 12) - 1
    return range(Math.max(0, maxPe + 1)).map(pe => peLabel(pe, descriptor))
  }, [descriptor, meta?.peCount, allPeItems])
  const portItems = useMemo(() => range(meta?.portCount || 28).sort(comparePorts(descriptor)), [descriptor, meta?.portCount])
  const rename = async () => { const nickname = prompt('Trace nickname', trace?.nickname || ''); if (nickname === null) return; await fetch(`/api/traces/${encodeURIComponent(selected)}/nickname`, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ nickname }) }); refresh() }
  const updateView = useCallback((next: Range) => setView(([start, end]) => Math.abs(start - next[0]) < 1 && Math.abs(end - next[1]) < 1 ? [start, end] : next), [])
  return <main>
    <header className="topbar"><div className="brand"><span className="mark">T</span><div><b>Telemetry Viewer</b><small>HardCilk trace analysis</small></div></div><div className="trace-picker"><label>TRACE BUNDLE<select className={trace?.simulation ? 'sim-selected' : undefined} value={selected} onChange={event => { setLoading(true); setMeta(null); setData(null); setSelected(event.target.value) }}>{traces.map(row => <option key={row.id} value={row.id} className={row.simulation ? 'sim-option' : undefined} style={row.simulation ? { color: '#b8860b' } : undefined}>{row.nickname || row.name}{row.simulation ? ' (sim)' : ''} · {row.sizeLabel}</option>)}</select></label><button className="icon-button" onClick={rename} disabled={!selected} title="Rename trace">Edit name</button></div><div className="live"><i /> watching saved_bundles</div></header>
    <div className="workspace"><aside>
      <div className="trace-info"><span>TRACE OVERVIEW</span><b>{trace?.nickname || trace?.name || 'No bundles found'}</b>{meta && <><small>{meta.statusCount.toLocaleString()} status events · {meta.bandwidthCount.toLocaleString()} bandwidth windows</small><small>{fmtCycle(meta.duration)} cycles · {(meta.duration / 1e8).toFixed(4)} seconds</small><small>{meta.headerless ? 'No embedded port descriptor' : `${descriptor?.design || 'Design'} descriptor · ${peItems.length} PEs${internalItems.length ? ` · ${internalItems.length} internal rows` : ''}${congestionItems.length ? ` · ${congestionItems.length} congestion rows` : ''} · ${meta.portCount} ports`}</small></>}</div>
      <Checks title="PROCESSING ELEMENTS" items={peItems} selected={pes} setSelected={setPes} label={item => peLabels[item] || `PE ${item}`} />
      {internalItems.length > 0 && <Checks title="INTERNAL SIGNALS" items={internalItems} selected={pes} setSelected={setPes} label={item => peLabels[item] || `Internal ${item}`} />}
      {congestionItems.length > 0 && <Checks title="SCHEDULER CONGESTION" items={congestionItems} selected={pes} setSelected={setPes} label={item => peLabels[item] || `Scheduler ${item}`} />}
      <Checks title="MEMORY PORTS" items={portItems} selected={ports} setSelected={setPorts} label={item => portLabel(item, descriptor, peLabels)} />
    </aside><section className="content">
      <div className="toolbar"><div><h1>Trace timeline</h1><p>Use the controls below to pan or zoom. Page scrolling remains vertical.</p></div><div className="view-toggle"><button className={viewMode === 'timeline' ? 'active' : ''} onClick={() => setViewMode('timeline')}>Timeline</button><button className={viewMode === 'grouped' ? 'active' : ''} onClick={() => setViewMode('grouped')}>Group by port</button></div><div className="legend"><span title="We accepted a new task this cycle (input handshake complete)."><i style={{background:COLORS[1]}}/>Active</span><span title="We accepted a new task, and we have an output ready to deposit but the output sink is not ready."><i className="striped-active"/>Active blocked</span><span title="An input was ready to come in, but we did not take it this cycle."><i style={{background:COLORS[2]}}/>Stalled</span><span title="No input is coming in and output is not blocked; we accepted an input and have not yet produced its output."><i style={{background:COLORS[3]}}/>Finishing</span><span title="Same as Finishing, but we cannot deposit the result because the output sink is not ready."><i className="striped"/>Finishing blocked</span><span title="Nothing is ready for the PE."><i style={{background:COLORS[0]}}/>Waiting</span><span title="Scheduler networkCongested flag asserted."><i style={{background:CONGESTION_COLORS[1]}}/>Congested</span><span title="Internal diagnostic handshake transfer."><i style={{background:HANDSHAKE_COLORS.transfer}}/>Internal transfer</span><span title="Internal diagnostic handshake valid but not ready."><i style={{background:HANDSHAKE_COLORS.blocked}}/>Internal blocked</span><span title="Memory read traffic on this port (bytes per cycle)."><i className="line read"/>Read</span><span title="Memory write traffic on this port (bytes per cycle)."><i className="line write"/>Write</span></div></div>
      {meta && <RangeControls view={view} duration={meta.duration} setView={updateView} />}
      {loading || !data ? <div className="loading"><span />{loading ? 'Decoding trace…' : traces.length ? 'Loading viewport…' : 'Add a .bin bundle to saved_bundles'}</div> : <Timeline traceId={selected} data={data} view={view} setView={updateView} visiblePEs={pes} visiblePorts={ports} expanded={expanded} mode={viewMode} descriptor={descriptor} peLabels={peLabels} onExpand={port => setExpanded(current => { const next = new Set(current); next.has(port) ? next.delete(port) : next.add(port); return next })}/>} 
    </section></div>
  </main>
}
