// Decodes a .bin telemetry bundle into an on-disk index directory that the viewer queries
// lazily. Nothing proportional to the trace size is ever held in memory: rows are streamed
// straight to per-row files, and each row also gets a level-of-detail pyramid so a zoomed-out
// viewport reads a few kilobytes instead of tens of millions of segments.
//
// usage: trace_decoder <input.bin> <plan.txt> <out_dir>
//
// The plan file tells the decoder which rows to emit and how to build each one out of the
// status word, so the viewer's descriptor logic stays in TypeScript:
//
//   peCount 21
//   portCount 14
//   row hs 1 0:4:0            # handshake row, output accounting on, nibble at bit 0
//   row pt 0 16:2:0,18:2:2    # passthrough row, two 2-bit fields packed at dest 0 and 2
//
// Output directory layout (meta.bin is written last and doubles as the "complete" marker):
//
//   s<stream>.l<level>.f   u32 LE cycle starts, count + 1 entries (final entry is the end)
//   s<stream>.l<level>.s   u8 states, count entries
//   bw.c / bw.r / bw.w     bandwidth cycles (f64 LE), read bytes, write bytes
//   meta.bin               header + per-stream level counts
//
// Stream 2*row is the row's state timeline, stream 2*row+1 its raw field bits.

use std::env;
use std::fs::{self, File};
use std::io::{self, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, SyncSender};
use std::sync::Arc;
use std::thread;

const INDEX_MAGIC: &[u8; 8] = b"TVIDX004";
const INDEX_VERSION: u32 = 4;
const CHUNK_SIZE: usize = 16 * 1024 * 1024;
const IO_BUFFER: usize = 1 << 20;
const LOD_FACTOR: usize = 8;
const LOD_MIN: usize = 2048;
const BATCH_LEN: usize = 1 << 15;

fn invalid(message: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

struct Extract {
    bit: u32,
    width: u32,
    dest: u32,
}

struct RowSpec {
    extracts: Vec<Extract>,
    handshake: bool,
    output_accounting: bool,
}

impl RowSpec {
    #[inline(always)]
    fn raw(&self, bits: u128) -> u8 {
        let mut value: u32 = 0;
        for extract in &self.extracts {
            let mask = (1u128 << extract.width) - 1;
            value |= (((bits >> extract.bit) & mask) as u32) << extract.dest;
        }
        value as u8
    }
}

struct Plan {
    pe_count: usize,
    port_count: usize,
    rows: Vec<RowSpec>,
}

fn parse_plan(path: &Path) -> io::Result<Plan> {
    let text = fs::read_to_string(path)?;
    let mut pe_count = 0usize;
    let mut port_count = 0usize;
    let mut rows = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut parts = line.split_whitespace();
        let keyword = parts.next().unwrap_or("");
        match keyword {
            "peCount" | "portCount" => {
                let value = parts
                    .next()
                    .and_then(|value| value.parse::<usize>().ok())
                    .ok_or_else(|| invalid(format!("plan: bad {keyword}")))?;
                if keyword == "peCount" {
                    pe_count = value;
                } else {
                    port_count = value;
                }
            }
            "row" => {
                let mode = parts.next().unwrap_or("");
                let accounting = parts.next().unwrap_or("0") == "1";
                let fields = parts.next().unwrap_or("");
                let mut extracts = Vec::new();
                for field in fields.split(',').filter(|field| !field.is_empty()) {
                    let mut numbers = field.split(':');
                    let mut next = || {
                        numbers
                            .next()
                            .and_then(|value| value.parse::<u32>().ok())
                            .ok_or_else(|| invalid(format!("plan: bad extract '{field}'")))
                    };
                    let bit = next()?;
                    let width = next()?;
                    let dest = next()?;
                    if width == 0 || width > 8 || bit + width > 128 || dest + width > 8 {
                        return Err(invalid(format!("plan: extract out of range '{field}'")));
                    }
                    extracts.push(Extract { bit, width, dest });
                }
                rows.push(RowSpec {
                    extracts,
                    handshake: mode == "hs",
                    output_accounting: accounting,
                });
            }
            _ => return Err(invalid(format!("plan: unknown directive '{keyword}'"))),
        }
    }
    Ok(Plan {
        pe_count,
        port_count,
        rows,
    })
}

/// Append-only writer for one stream: level 0 at full fidelity plus level 1, which is derived
/// on the fly so the deeper pyramid levels can be built from a 1/8-size file instead of
/// re-reading everything. Segments tile the timeline, so only the start cycle is stored and a
/// run of equal states collapses into one entry.
struct StreamOut {
    from0: BufWriter<File>,
    state0: BufWriter<File>,
    count0: usize,
    last0: i16,
    from1: BufWriter<File>,
    state1: BufWriter<File>,
    count1: usize,
    last1: i16,
}

fn create(dir: &Path, stream: usize, level: usize, suffix: &str) -> io::Result<BufWriter<File>> {
    let path = dir.join(format!("s{stream}.l{level}.{suffix}"));
    Ok(BufWriter::with_capacity(IO_BUFFER, File::create(path)?))
}

impl StreamOut {
    fn new(dir: &Path, stream: usize) -> io::Result<Self> {
        Ok(Self {
            from0: create(dir, stream, 0, "f")?,
            state0: create(dir, stream, 0, "s")?,
            count0: 0,
            last0: -1,
            from1: create(dir, stream, 1, "f")?,
            state1: create(dir, stream, 1, "s")?,
            count1: 0,
            last1: -1,
        })
    }

    #[inline(always)]
    fn push(&mut self, from: u32, state: u8) -> io::Result<()> {
        if self.last0 == state as i16 {
            return Ok(());
        }
        if self.count0 % LOD_FACTOR == 0 && self.last1 != state as i16 {
            self.from1.write_all(&from.to_le_bytes())?;
            self.state1.write_all(&[state])?;
            self.count1 += 1;
            self.last1 = state as i16;
        }
        self.from0.write_all(&from.to_le_bytes())?;
        self.state0.write_all(&[state])?;
        self.count0 += 1;
        self.last0 = state as i16;
        Ok(())
    }

    /// Closes levels 0 and 1, then folds level 1 down by LOD_FACTOR until a level is small
    /// enough to render whole. Returns the segment count of every level.
    fn finish(mut self, dir: &Path, stream: usize, duration: u32) -> io::Result<Vec<u64>> {
        self.from0.write_all(&duration.to_le_bytes())?;
        self.from0.flush()?;
        self.state0.flush()?;
        self.from1.write_all(&duration.to_le_bytes())?;
        self.from1.flush()?;
        self.state1.flush()?;
        drop(self.from0);
        drop(self.state0);
        drop(self.from1);
        drop(self.state1);

        let mut counts = vec![self.count0 as u64];
        if self.count0 == 0 {
            fs::remove_file(dir.join(format!("s{stream}.l1.f")))?;
            fs::remove_file(dir.join(format!("s{stream}.l1.s")))?;
            return Ok(counts);
        }
        counts.push(self.count1 as u64);
        if self.count0 <= LOD_MIN {
            // Level 1 is redundant when level 0 already fits in a single viewport read.
            fs::remove_file(dir.join(format!("s{stream}.l1.f")))?;
            fs::remove_file(dir.join(format!("s{stream}.l1.s")))?;
            counts.pop();
            return Ok(counts);
        }

        let mut from = read_u32s(&dir.join(format!("s{stream}.l1.f")))?;
        let mut state = read_bytes(&dir.join(format!("s{stream}.l1.s")))?;
        let mut level = 1;
        while state.len() > LOD_MIN {
            level += 1;
            let (next_from, next_state) = reduce(&from, &state, duration);
            let mut out_from = create(dir, stream, level, "f")?;
            let mut out_state = create(dir, stream, level, "s")?;
            for value in &next_from {
                out_from.write_all(&value.to_le_bytes())?;
            }
            out_state.write_all(&next_state)?;
            out_from.flush()?;
            out_state.flush()?;
            counts.push(next_state.len() as u64);
            from = next_from;
            state = next_state;
        }
        Ok(counts)
    }
}

fn reduce(from: &[u32], state: &[u8], duration: u32) -> (Vec<u32>, Vec<u8>) {
    let mut out_from = Vec::with_capacity(state.len() / LOD_FACTOR + 2);
    let mut out_state = Vec::with_capacity(state.len() / LOD_FACTOR + 1);
    let mut last: i16 = -1;
    let mut index = 0;
    while index < state.len() {
        let value = state[index];
        if value as i16 != last {
            out_from.push(from[index]);
            out_state.push(value);
            last = value as i16;
        }
        index += LOD_FACTOR;
    }
    out_from.push(duration);
    (out_from, out_state)
}

fn read_u32s(path: &Path) -> io::Result<Vec<u32>> {
    let bytes = fs::read(path)?;
    Ok(bytes
        .chunks_exact(4)
        .map(|chunk| u32::from_le_bytes(chunk.try_into().unwrap()))
        .collect())
}

fn read_bytes(path: &Path) -> io::Result<Vec<u8>> {
    fs::read(path)
}

struct Row {
    spec: RowSpec,
    in_flight: u64,
    states: StreamOut,
    raws: StreamOut,
}

impl Row {
    #[inline(always)]
    fn advance(&mut self, from: u32, to: u32, bits: u128) -> io::Result<()> {
        let to = to.max(from);
        let cycles = (to - from) as u64;
        let raw = self.spec.raw(bits);
        let state = if self.spec.handshake {
            let in_valid = raw & 1 != 0;
            let in_ready = raw & 2 != 0;
            let out_valid = raw & 4 != 0;
            let out_ready = raw & 8 != 0;
            let finishing = self.in_flight > 0 || out_valid;
            let output_blocked = self.spec.output_accounting && out_valid && !out_ready;
            let state = if in_valid {
                if in_ready {
                    if output_blocked {
                        5
                    } else {
                        1
                    }
                } else {
                    2
                }
            } else if self.spec.output_accounting && finishing {
                if output_blocked {
                    4
                } else {
                    3
                }
            } else {
                0
            };
            if in_valid && in_ready {
                self.in_flight = self.in_flight.saturating_add(cycles);
            }
            if out_valid && out_ready {
                self.in_flight = self.in_flight.saturating_sub(cycles);
            }
            state
        } else {
            raw
        };
        if to > from {
            self.states.push(from, state)?;
            self.raws.push(from, raw)?;
        }
        Ok(())
    }
}

enum Message {
    Batch(Arc<Vec<(u32, u128)>>),
    Finish(u32),
}

fn read_header(file: &mut File) -> io::Result<(u32, u64)> {
    let mut header = [0u8; 32];
    file.read_exact(&mut header)?;
    if &header[0..8] != b"HCKTRACE" {
        return Ok((1, 0));
    }
    Ok((
        u32::from_le_bytes(header[8..12].try_into().unwrap()),
        u64::from_le_bytes(header[24..32].try_into().unwrap()),
    ))
}

struct Bandwidth {
    port_count: usize,
    cycles: BufWriter<File>,
    read: BufWriter<File>,
    write: BufWriter<File>,
    current_cycle: u64,
    read_slots: [u8; 31],
    write_slots: [u8; 31],
    seen_mask: u8,
    pending_cycle: Option<u64>,
    count: u64,
    last_cycle: u64,
}

impl Bandwidth {
    fn new(dir: &Path, port_count: usize) -> io::Result<Self> {
        let open = |name: &str| -> io::Result<BufWriter<File>> {
            Ok(BufWriter::with_capacity(
                IO_BUFFER,
                File::create(dir.join(name))?,
            ))
        };
        Ok(Self {
            port_count,
            cycles: open("bw.c")?,
            read: open("bw.r")?,
            write: open("bw.w")?,
            current_cycle: 0,
            read_slots: [0; 31],
            write_slots: [0; 31],
            seen_mask: 0,
            pending_cycle: None,
            count: 0,
            last_cycle: 0,
        })
    }

    fn flush_window(&mut self) -> io::Result<()> {
        if self.seen_mask == 0 {
            return Ok(());
        }
        let cycle = self
            .pending_cycle
            .map(|value| value.saturating_sub(127))
            .unwrap_or(self.current_cycle);
        self.cycles.write_all(&(cycle as f64).to_le_bytes())?;
        self.read.write_all(&self.read_slots[..self.port_count])?;
        self.write.write_all(&self.write_slots[..self.port_count])?;
        self.count += 1;
        self.last_cycle = cycle;
        self.current_cycle = cycle.saturating_add(128);
        self.read_slots.fill(0);
        self.write_slots.fill(0);
        self.seen_mask = 0;
        self.pending_cycle = None;
        Ok(())
    }

    fn finish(mut self) -> io::Result<(u64, u64)> {
        self.flush_window()?;
        self.cycles.flush()?;
        self.read.flush()?;
        self.write.flush()?;
        let end = if self.count == 0 {
            0
        } else {
            self.last_cycle.saturating_add(128)
        };
        Ok((self.count, end))
    }
}

fn spawn_worker(
    dir: PathBuf,
    rows: Vec<(usize, RowSpec)>,
) -> io::Result<(SyncSender<Message>, thread::JoinHandle<io::Result<Vec<(usize, Vec<u64>)>>>)> {
    let mut prepared = Vec::with_capacity(rows.len());
    for (index, spec) in rows {
        prepared.push((
            index,
            Row {
                spec,
                in_flight: 0,
                states: StreamOut::new(&dir, index * 2)?,
                raws: StreamOut::new(&dir, index * 2 + 1)?,
            },
        ));
    }
    let (sender, receiver) = mpsc::sync_channel::<Message>(4);
    let handle = thread::spawn(move || -> io::Result<Vec<(usize, Vec<u64>)>> {
        let mut previous: Option<(u32, u128)> = None;
        let mut duration = 0u32;
        for message in receiver {
            match message {
                Message::Batch(batch) => {
                    for &(cycle, bits) in batch.iter() {
                        if let Some((from, from_bits)) = previous {
                            for (_, row) in prepared.iter_mut() {
                                row.advance(from, cycle, from_bits)?;
                            }
                        }
                        previous = Some((cycle, bits));
                    }
                }
                Message::Finish(end) => {
                    duration = end;
                    if let Some((from, from_bits)) = previous {
                        for (_, row) in prepared.iter_mut() {
                            row.advance(from, end, from_bits)?;
                        }
                    }
                    break;
                }
            }
        }
        let mut result = Vec::with_capacity(prepared.len() * 2);
        for (index, row) in prepared {
            result.push((index * 2, row.states.finish(&dir, index * 2, duration)?));
            result.push((index * 2 + 1, row.raws.finish(&dir, index * 2 + 1, duration)?));
        }
        Ok(result)
    });
    Ok((sender, handle))
}

fn decode(input: &Path, plan: &Plan, dir: &Path) -> io::Result<()> {
    let mut file = File::open(input)?;
    let (format_version, beats_offset) = read_header(&mut file)?;
    file.seek(SeekFrom::Start(beats_offset))?;

    let worker_count = thread::available_parallelism()
        .map(|value| value.get().saturating_sub(1).max(1))
        .unwrap_or(4)
        .min(plan.rows.len().max(1));
    let mut buckets: Vec<Vec<(usize, RowSpec)>> = (0..worker_count).map(|_| Vec::new()).collect();
    for (index, spec) in plan.rows.iter().enumerate() {
        buckets[index % worker_count].push((
            index,
            RowSpec {
                extracts: spec
                    .extracts
                    .iter()
                    .map(|extract| Extract {
                        bit: extract.bit,
                        width: extract.width,
                        dest: extract.dest,
                    })
                    .collect(),
                handshake: spec.handshake,
                output_accounting: spec.output_accounting,
            },
        ));
    }
    let mut senders = Vec::with_capacity(worker_count);
    let mut handles = Vec::with_capacity(worker_count);
    for bucket in buckets {
        let (sender, handle) = spawn_worker(dir.to_path_buf(), bucket)?;
        senders.push(sender);
        handles.push(handle);
    }

    let mut bandwidth = Bandwidth::new(dir, plan.port_count)?;
    let mut batch: Vec<(u32, u128)> = Vec::with_capacity(BATCH_LEN);
    let mut status_count: u64 = 0;
    let mut previous_bits: u128 = 0;
    let mut previous_cycle: u32 = 0;
    let mut has_previous = false;

    let (chunk_sender, chunk_receiver) = mpsc::sync_channel::<io::Result<Vec<u8>>>(2);
    let reader = thread::spawn(move || loop {
        let mut buffer = vec![0u8; CHUNK_SIZE];
        match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(read) => {
                buffer.truncate(read);
                if chunk_sender.send(Ok(buffer)).is_err() {
                    break;
                }
            }
            Err(error) => {
                let _ = chunk_sender.send(Err(error));
                break;
            }
        }
    });

    let send_batch = |batch: &mut Vec<(u32, u128)>| -> io::Result<()> {
        if batch.is_empty() {
            return Ok(());
        }
        let shared = Arc::new(std::mem::replace(batch, Vec::with_capacity(BATCH_LEN)));
        for sender in &senders {
            sender
                .send(Message::Batch(Arc::clone(&shared)))
                .map_err(|_| invalid("decoder worker stopped early".into()))?;
        }
        Ok(())
    };

    'outer: for chunk in &chunk_receiver {
        let bytes = chunk?;
        let usable = bytes.len() - bytes.len() % 32;
        for beat in bytes[..usable].chunks_exact(32) {
            if beat.iter().all(|value| *value == 0) {
                break 'outer;
            }
            for slot in [&beat[0..16], &beat[16..32]] {
                let header = slot[0];
                if header == 0 {
                    continue;
                }
                if header == 1 {
                    let bundle = u128::from_le_bytes(slot.try_into().unwrap());
                    let (status, cycle) = if format_version >= 2 {
                        (
                            (bundle >> 8) & ((1u128 << 88) - 1),
                            (bundle >> 96) & ((1u128 << 32) - 1),
                        )
                    } else {
                        (
                            (bundle >> 8) & ((1u128 << 48) - 1),
                            (bundle >> 56) & ((1u128 << 72) - 1),
                        )
                    };
                    if cycle > u32::MAX as u128 {
                        return Err(invalid(format!(
                            "cycle {cycle} exceeds the 32-bit range the index supports"
                        )));
                    }
                    status_count += 1;
                    if has_previous && status == previous_bits {
                        continue;
                    }
                    let cycle = cycle as u32;
                    batch.push((cycle, status));
                    if batch.len() >= BATCH_LEN {
                        send_batch(&mut batch)?;
                    }
                    previous_bits = status;
                    previous_cycle = cycle;
                    has_previous = true;
                    continue;
                }
                if (2..=7).contains(&header) {
                    let bit = 1u8 << (header - 2);
                    if bandwidth.seen_mask & bit != 0 {
                        bandwidth.flush_window()?;
                    }
                    bandwidth.seen_mask |= bit;
                    let is_write = header >= 5;
                    let sub = (header - if is_write { 5 } else { 2 }) as usize;
                    let target = if is_write {
                        &mut bandwidth.write_slots
                    } else {
                        &mut bandwidth.read_slots
                    };
                    for offset in 0..15usize {
                        let port = sub * 15 + offset;
                        if port < 31 {
                            target[port] = slot[1 + offset];
                        }
                    }
                    if bandwidth.seen_mask == 0b11_1111 && bandwidth.pending_cycle.is_some() {
                        bandwidth.flush_window()?;
                    }
                    continue;
                }
                if header == 8 {
                    if bandwidth.pending_cycle.is_some() {
                        bandwidth.flush_window()?;
                    }
                    let bundle = u128::from_le_bytes(slot.try_into().unwrap());
                    let cycle = if format_version >= 2 {
                        (bundle >> 53) & ((1u128 << 32) - 1)
                    } else {
                        (bundle >> 53) & ((1u128 << 72) - 1)
                    };
                    bandwidth.pending_cycle = Some(cycle.min(u64::MAX as u128) as u64);
                    if bandwidth.seen_mask == 0b11_1111 {
                        bandwidth.flush_window()?;
                    }
                }
            }
        }
    }
    drop(chunk_receiver);
    let _ = reader.join();

    send_batch(&mut batch)?;
    let (bandwidth_count, bandwidth_end) = bandwidth.finish()?;
    let duration_wide = if has_previous {
        (previous_cycle as u64).max(bandwidth_end).max(1)
    } else {
        bandwidth_end.max(1)
    };
    if duration_wide > u32::MAX as u64 {
        return Err(invalid(format!(
            "trace duration {duration_wide} exceeds the 32-bit range the index supports"
        )));
    }
    let duration = duration_wide as u32;
    for sender in &senders {
        sender
            .send(Message::Finish(duration))
            .map_err(|_| invalid("decoder worker stopped early".into()))?;
    }
    drop(senders);

    let mut levels: Vec<Vec<u64>> = vec![Vec::new(); plan.rows.len() * 2];
    for handle in handles {
        for (stream, counts) in handle
            .join()
            .map_err(|_| invalid("decoder worker panicked".into()))??
        {
            levels[stream] = counts;
        }
    }

    write_meta(
        dir,
        plan,
        duration,
        status_count,
        bandwidth_count,
        &levels,
    )
}

fn write_meta(
    dir: &Path,
    plan: &Plan,
    duration: u32,
    status_count: u64,
    bandwidth_count: u64,
    levels: &[Vec<u64>],
) -> io::Result<()> {
    let mut out = BufWriter::new(File::create(dir.join("meta.bin.part"))?);
    out.write_all(INDEX_MAGIC)?;
    out.write_all(&INDEX_VERSION.to_le_bytes())?;
    out.write_all(&(levels.len() as u32).to_le_bytes())?;
    out.write_all(&(plan.rows.len() as u32).to_le_bytes())?;
    out.write_all(&(plan.port_count as u32).to_le_bytes())?;
    out.write_all(&(plan.pe_count as u32).to_le_bytes())?;
    out.write_all(&(duration as u64).to_le_bytes())?;
    out.write_all(&status_count.to_le_bytes())?;
    out.write_all(&bandwidth_count.to_le_bytes())?;
    for counts in levels {
        out.write_all(&(counts.len() as u32).to_le_bytes())?;
        for count in counts {
            out.write_all(&count.to_le_bytes())?;
        }
    }
    out.flush()?;
    drop(out);
    fs::rename(dir.join("meta.bin.part"), dir.join("meta.bin"))
}

fn main() -> io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() != 4 {
        eprintln!("usage: trace_decoder <input.bin> <plan.txt> <out_dir>");
        std::process::exit(2);
    }
    let input = Path::new(&args[1]);
    let plan = parse_plan(Path::new(&args[2]))?;
    if plan.pe_count > 22 || plan.port_count > 31 {
        eprintln!(
            "unsupported trace dimensions: pe_count={}, port_count={}",
            plan.pe_count, plan.port_count
        );
        std::process::exit(2);
    }
    let dir = Path::new(&args[3]);
    fs::create_dir_all(dir)?;
    decode(input, &plan, dir)
}
