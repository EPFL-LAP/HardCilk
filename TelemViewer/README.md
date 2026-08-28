# TelemViewer server deployment

This package contains the TelemViewer source code and dependency lockfiles. Generated files, dependency folders, decoded trace indexes, compiled native binaries, and existing telemetry bundles are intentionally omitted. `saved_bundles/` is included empty; copy `.bin` trace bundles into it after deployment.

## Requirements

- Linux x86-64 or ARM64 (macOS also works for local development)
- Node.js 22.12 or newer
- pnpm 10 (the lockfile is included)
- A Rust toolchain with Cargo
- A C/C++ build toolchain (`build-essential` on Debian/Ubuntu)

The server needs free disk space for `telem/.trace-index`. A decoded index can be several times larger than its source bundle. By default the index cache is capped at 48 GB; set `TELEM_INDEX_CACHE_GB` when starting the server to change that limit.

## Install and build

Extract the archive, then run these commands from its top-level directory:

```bash
cd telem
corepack enable
corepack prepare pnpm@10 --activate
pnpm install --frozen-lockfile
pnpm build
```

`pnpm install` downloads the JavaScript dependencies. `pnpm build` compiles the Rust trace decoder, checks the TypeScript source, and creates the frontend production build in `telem/dist/`.

On a fresh Debian/Ubuntu server, install the system prerequisites first. Install Node.js 22 using your normal Node.js package source, then install Rust and the native build tools:

```bash
sudo apt-get update
sudo apt-get install -y build-essential curl
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source "$HOME/.cargo/env"
```

## Add telemetry bundles

Place trace files in the top-level `saved_bundles/` directory, alongside `telem/`:

```text
TelemViewer/
├── saved_bundles/
│   └── example_telemetry.bin
└── telem/
```

Do not move `saved_bundles` inside `telem`; the server expects these two directories to be siblings. TelemViewer creates rebuildable decoded indexes under `telem/.trace-index/` as bundles are opened.

## Start the server

From the `telem/` directory:

```bash
TELEM_INDEX_CACHE_GB=48 pnpm start
```

The server listens on all interfaces at port `4173`. Open `http://SERVER_IP:4173/`. Allow TCP port 4173 in the host or cloud firewall if accessing it directly. Keep this service on a trusted network or put it behind an authenticated reverse proxy; the app has no built-in authentication.

To use another index-cache limit, replace `48` with the desired number of GB. To use another port, run:

```bash
TELEM_INDEX_CACHE_GB=48 pnpm exec vite preview --host 0.0.0.0 --port 8080 --strictPort
```

## Keep it running with systemd (optional)

Create `/etc/systemd/system/telemviewer.service` and adjust `User`, `WorkingDirectory`, and the pnpm path returned by `command -v pnpm`:

```ini
[Unit]
Description=TelemViewer
After=network.target

[Service]
Type=simple
User=YOUR_USER
WorkingDirectory=/opt/TelemViewer/telem
Environment=TELEM_INDEX_CACHE_GB=48
ExecStart=/absolute/path/to/pnpm start
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Then enable and start it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now telemviewer
sudo systemctl status telemviewer
```

After updating the source, run `pnpm install --frozen-lockfile`, `pnpm build`, and `sudo systemctl restart telemviewer` again.

## Development mode

For local development with hot reload:

```bash
cd telem
pnpm install --frozen-lockfile
pnpm dev -- --host 0.0.0.0
```
