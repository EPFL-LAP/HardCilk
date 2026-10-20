`timescale 1ns/1ps
// Unit test of the HBMEX V80 core (hbmexF_HbmexV80Full): random multi-ID INCR bursts
// (len 0..15, 256-bit) in, an out-of-order responder behind it (different IDs reorder,
// same ID in order), check: only AxLEN=0 at the output, ID < 32, address = swap(L),
// read data = what was written, one B per burst, RLAST placement, RID/BID restore.
module tb;
  localparam G = 14;
  logic clk = 0, rst = 1;
  always #2 clk = ~clk;
  // DUT slave side
  logic arv, arr, awv, awr, wv, wr_, wl, rv, rr, rl, bv, br;
  logic [11:0] arid, awid, rid, bid; logic [63:0] araddr, awaddr; logic [7:0] arlen, awlen;
  logic [255:0] wd, rd; logic [31:0] ws; logic [1:0] rresp, bresp;
  // DUT master side
  logic marv, marr, mawv, mawr, mwv, mwr, mwl, mrv, mrr, mrl, mbv, mbr;
  logic [4:0] marid, mawid, mrid, mbid; logic [63:0] maraddr, mawaddr; logic [7:0] marlen, mawlen;
  logic [255:0] mwd, mrd; logic [31:0] mws; logic [2:0] marsize, mawsize; logic [1:0] marburst, mawburst;
`ifdef STRIPE_ONLY
  hbmexS_HbmexV80Stripe dut(
`else
  hbmexF_HbmexV80Full dut(
`endif
    .clock(clk), .reset(rst),
    .S_AXI_ARREADY(arr), .S_AXI_ARVALID(arv), .S_AXI_ARID(arid), .S_AXI_ARADDR(araddr), .S_AXI_ARLEN(arlen),
    .S_AXI_ARSIZE(3'd5), .S_AXI_ARBURST(2'd1), .S_AXI_ARCACHE(4'd3), .S_AXI_ARPROT(3'd0),
    .S_AXI_RREADY(rr), .S_AXI_RVALID(rv), .S_AXI_RID(rid), .S_AXI_RDATA(rd), .S_AXI_RRESP(rresp), .S_AXI_RLAST(rl),
    .S_AXI_AWREADY(awr), .S_AXI_AWVALID(awv), .S_AXI_AWID(awid), .S_AXI_AWADDR(awaddr), .S_AXI_AWLEN(awlen),
    .S_AXI_AWSIZE(3'd5), .S_AXI_AWBURST(2'd1), .S_AXI_AWCACHE(4'd3), .S_AXI_AWPROT(3'd0),
    .S_AXI_WREADY(wr_), .S_AXI_WVALID(wv), .S_AXI_WDATA(wd), .S_AXI_WSTRB(ws), .S_AXI_WLAST(wl),
    .S_AXI_BREADY(br), .S_AXI_BVALID(bv), .S_AXI_BID(bid), .S_AXI_BRESP(bresp),
    .M_AXI_ARREADY(marr), .M_AXI_ARVALID(marv), .M_AXI_ARID(marid), .M_AXI_ARADDR(maraddr), .M_AXI_ARLEN(marlen),
    .M_AXI_ARSIZE(marsize), .M_AXI_ARBURST(marburst), .M_AXI_ARCACHE(), .M_AXI_ARPROT(),
    .M_AXI_RREADY(mrr), .M_AXI_RVALID(mrv), .M_AXI_RID(mrid), .M_AXI_RDATA(mrd), .M_AXI_RRESP(2'd0), .M_AXI_RLAST(mrl),
    .M_AXI_AWREADY(mawr), .M_AXI_AWVALID(mawv), .M_AXI_AWID(mawid), .M_AXI_AWADDR(mawaddr), .M_AXI_AWLEN(mawlen),
    .M_AXI_AWSIZE(mawsize), .M_AXI_AWBURST(mawburst), .M_AXI_AWCACHE(), .M_AXI_AWPROT(),
    .M_AXI_WREADY(mwr), .M_AXI_WVALID(mwv), .M_AXI_WDATA(mwd), .M_AXI_WSTRB(mws), .M_AXI_WLAST(mwl),
    .M_AXI_BREADY(mbr), .M_AXI_BVALID(mbv), .M_AXI_BID(mbid), .M_AXI_BRESP(2'd0));

  function automatic longint unsigned swp(input longint unsigned a);
    longint unsigned p = a;
    p[34:30] = a[G+4:G]; p[G+4:G] = a[34:30];
    return p;
  endfunction

  // ---------------- responder: memory at physical addresses ----------------
  logic [255:0] mem [longint unsigned];
  typedef struct { int id; longint unsigned a; int beats; int due; } req_t;
  req_t rq[$]; req_t wq[$]; int wbeats_pending[$];
  int cyc = 0; int errors = 0;
  always @(posedge clk) cyc++;
  always @(posedge clk) begin marr <= ($urandom % 4) != 0; mawr <= ($urandom % 4) != 0; mwr <= (($urandom % 3) != 0) && (awq.size() > 1 || (awq.size() == 1 && !(mwv && mwr && mwl))); end
  // AW/W: record writes; W applies to the oldest AW that still needs beats
  req_t awq[$]; int awbeat = 0;
  always @(posedge clk) if (!rst) begin
    if (marv && marr) begin
      automatic req_t r; r.id = marid; r.a = maraddr; r.beats = marlen + 1; r.due = cyc + 20 + ($urandom % 60);
`ifndef STRIPE_ONLY
      if (marlen != 0) begin errors++; $display("ERR: ARLEN %0d at output", marlen); end
`endif
      rq.push_back(r);
    end
    if (mawv && mawr) begin
      automatic req_t r; r.id = mawid; r.a = mawaddr; r.beats = mawlen + 1; r.due = 0;
      awq.push_back(r);
`ifndef STRIPE_ONLY
      if (mawlen != 0) begin errors++; $display("ERR: AWLEN %0d at output", mawlen); end
`endif
    end
    if (mwv && mwr) begin
      if (awq.size() == 0) begin errors++; $display("ERR: W before AW"); end
      else begin
        mem[awq[0].a + 32*awbeat] = mwd;
        if (mwl != (awbeat == awq[0].beats - 1)) begin errors++; $display("ERR: WLAST misplaced"); end
        awbeat++;
        if (awbeat == awq[0].beats) begin automatic req_t r = awq.pop_front(); r.due = cyc + 10 + ($urandom % 40); wq.push_back(r); awbeat = 0; end
      end
    end
  end
  // R: pick a due request whose ID has no older pending request (same-ID order)
  int rsel = -1, rbeat = 0;
  function automatic int pick(ref req_t q[$]);
    for (int i = 0; i < q.size(); i++) begin
      bit older = 0;
      for (int j = 0; j < i; j++) if (q[j].id == q[i].id) older = 1;
      if (!older && q[i].due <= cyc && ($urandom % 2)) return i;
    end
    return -1;
  endfunction
  always @(posedge clk) begin
    if (rst) begin mrv <= 0; mbv <= 0; end
    else begin
      if (mrv && mrr) begin
        if (mrl) begin rq.delete(rsel); rsel = -1; rbeat = 0; mrv <= 0; end
        else begin rbeat++; end
      end
      if (rsel < 0 || !mrv) begin
        if (rsel < 0) rsel = pick(rq);
      end
      if (rsel >= 0) begin
        mrv <= 1; mrid <= rq[rsel].id; mrl <= (rbeat == rq[rsel].beats - 1);
        mrd <= mem.exists(rq[rsel].a + 32*rbeat) ? mem[rq[rsel].a + 32*rbeat] : 256'hdead;
      end
      if (mbv && mbr) mbv <= 0;
      if (!mbv || (mbv && mbr)) begin
        automatic int k = pick(wq);
        if (k >= 0) begin mbv <= 1; mbid <= wq[k].id; wq.delete(k); end
      end
    end
  end
  // ---------------- master: logical traffic ----------------
  localparam longint unsigned BASE = 64'h40_0000_0000;
  typedef struct { int id; longint unsigned a; int beats; } m_t;
  m_t pend_r[$]; int pend_b[$];   // expected order per ID is checked via per-ID queues
  m_t exp_r [int][$]; int exp_b [int][$];
  logic [255:0] model [longint unsigned];
  int n_rd = 0, n_wr = 0, n_rdone = 0, n_bdone = 0;
  function automatic logic [255:0] pat(longint unsigned a, int salt); return {8{a[31:0] ^ salt}}; endfunction
  // writes first (phase 1), then reads (phase 2), then mixed reads of written data
  int NB = 600;
  longint unsigned waddr [$]; int wlen [$];
  initial begin
    arv = 0; awv = 0; wv = 0; rr = 1; br = 1; ws = '1;
    repeat (10) @(posedge clk); rst = 0;
    // phase 1: random writes
    fork
      begin : aw_proc
        for (int i = 0; i < NB; i++) begin
          automatic int len = $urandom % 16;
          automatic longint unsigned a = BASE + (longint'($urandom % (1 << 21)) << 5);
          // keep inside 4KB (the 4KB splitter guarantees this upstream)
          if (((a & 12'hFFF) >> 5) + len + 1 > 128) a = a - (((a & 12'hFFF) >> 5) + len + 1 - 128) * 32;
          // spread over the whole 32 GB too
          a = a + (longint'($urandom % 32) << 30);
          waddr.push_back(a); wlen.push_back(len);
          awid = $urandom % 16; awaddr = a; awlen = len; awv = 1;
          @(posedge clk); while (!awr) @(posedge clk);
          awv = 0;
          exp_b[awid].push_back(i);
          n_wr++;
        end
      end
      begin : w_proc
        for (int i = 0; i < NB; i++) begin
          while (waddr.size() <= i) @(posedge clk);
          for (int b = 0; b <= wlen[i]; b++) begin
            wd = pat(waddr[i] + 32*b, 7); wl = (b == wlen[i]); wv = 1;
            model[waddr[i] + 32*b] = wd;
            @(posedge clk); while (!wr_) @(posedge clk);
            wv = 0;
          end
        end
      end
    join
    wait (n_bdone == NB);
    $display("phase 1 done at cycle %0d: %0d writes", cyc, NB);
    // check physical placement
    foreach (model[a]) if (!mem.exists(swp(a)) || mem[swp(a)] !== model[a]) begin
      errors++; if (errors < 5) $display("ERR: logical 0x%0h not at physical 0x%0h", a, swp(a));
    end
    // phase 2: read back every written burst with random IDs
    for (int i = 0; i < NB; i++) begin
      automatic m_t m; m.id = $urandom % 16; m.a = waddr[i]; m.beats = wlen[i] + 1;
      arid = m.id; araddr = m.a; arlen = wlen[i]; arv = 1;
      exp_r[m.id].push_back(m);
      @(posedge clk); while (!arr) @(posedge clk);
      arv = 0;
    end
    wait (n_rdone == NB);
    $display("phase 2 done at cycle %0d: %0d reads, errors %0d", cyc, NB, errors);
    if (errors == 0) $display("UNIT: PASS"); else $display("UNIT: FAIL (%0d)", errors);
    $finish;
  end
  // response checkers
  int rbeat_s [int];
  always @(posedge clk) if (!rst) begin
    if (rv && rr) begin
      if (exp_r[rid].size() == 0) begin errors++; $display("ERR: unexpected RID %0d", rid); end
      else begin
        automatic m_t m = exp_r[rid][0];
        automatic int b = rbeat_s.exists(rid) ? rbeat_s[rid] : 0;
        if (rd !== model[m.a + 32*b]) begin errors++; if (errors < 10) $display("ERR: rdata id %0d addr 0x%0h beat %0d", rid, m.a, b); end
        if (rl != (b == m.beats - 1)) begin errors++; $display("ERR: RLAST id %0d beat %0d of %0d", rid, b, m.beats); end
        if (rl) begin void'(exp_r[rid].pop_front()); rbeat_s[rid] = 0; n_rdone++; end else rbeat_s[rid] = b + 1;
      end
    end
    if (bv && br) begin
      if (exp_b[bid].size() == 0) begin errors++; $display("ERR: unexpected BID %0d", bid); end
      else begin void'(exp_b[bid].pop_front()); n_bdone++; end
    end
  end
  int c_ar=0, c_mar=0, c_mr=0, c_r=0;
  always @(posedge clk) begin
    if (arv&&arr) c_ar++; if (marv&&marr) c_mar++; if (mrv&&mrr) c_mr++; if (rv&&rr) c_r++;
    if (cyc % 5000 == 0) $display("cyc %0d: AR %0d mAR %0d mR %0d R %0d rq %0d rsel %0d mrv %0d", cyc, c_ar, c_mar, c_mr, c_r, rq.size(), rsel, mrv);
  end
  initial begin #2000000; $display("UNIT: TIMEOUT (deadlock?) wr %0d/%0d rd %0d", n_bdone, NB, n_rdone); $finish; end
endmodule
