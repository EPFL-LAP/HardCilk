#!/usr/bin/env python3
"""Compose independently elaborated CPU/HardCilk RTL and generate simulator bindings.
No Scala dependency is shared across the two projects. Port widths come from RTL.
"""
import argparse, json, re
from pathlib import Path

def ports(text, module):
    text = re.sub(r'//[^\n]*', '', text)
    header = re.search(r'\bmodule\s+'+module+r'\s*\((.*?)\);', text, re.S).group(1)
    result={}; direction=None; width=1
    for item in header.split(','):
        item=item.strip()
        m=re.fullmatch(r'(?:(input|output)\s+)?(?:wire\s+|logic\s+)?(?:\[(\d+):0\]\s*)?(\w+)',item)
        if not m: raise ValueError(item)
        if m[1]: direction=m[1]; width=int(m[2])+1 if m[2] else 1
        result[m[3]]=(direction,width)
    return result

def decl(name, spec, internal=False):
    direction,width=spec
    return f'{"wire" if internal else direction} {f"[{width-1}:0] " if width>1 else ""}{name}'

def compose(root):
    root=Path(root); project=json.loads((root/'project.json').read_text())
    hcdir=root/'src/rtl'/project['name']; arch=json.loads((hcdir/'architecture.json').read_text())
    hcname=arch['name']; out=root/'src/rtl/system';out.mkdir(parents=True,exist_ok=True)
    hctext=(hcdir/(hcname+'.sv')).read_text();hp=ports(hctext,hcname)
    external={'clock':('input',1),'reset':('input',1)}; internal={};body=[]; hconn={}; cpus=[]
    for name,spec in hp.items():
        if 'BindTo_PE_' in name: internal[name]=spec
        else: external[name]=spec
        hconn[name]=name
    readers=[]
    for task in arch['taskDescriptors']:
      taskname=task['name']; reader=bool(task.get('memoryReader'))
      # Memory readers have no control port or firmware; they are rdK, CPUs cpuK.
      folder,module,tag=('reader','HardCilkMemoryReader','ReaderRTL_') if reader else ('cpu','HardCilkCpu','CpuRTL_')
      cputext=(root/'src/rtl'/folder/taskname/(module+'.sv')).read_text()
      cp=ports(cputext,module); prefix=tag+taskname+'_'
      modules=re.findall(r'^module\s+(\w+)',cputext,re.M)
      for name in sorted(modules,key=len,reverse=True):
        cputext=re.sub(r'\b'+re.escape(name)+r'\b',prefix+name,cputext)
      (out/(taskname+'.sv')).write_text(cputext)
      for pe in range(task['numProcessingElements']):
        group=readers if reader else cpus
        idx=len(group); label=f'rd{idx}' if reader else f'cpu{idx}'; conn={}; group.append((label,task,pe))
        for name,spec in cp.items():
            if name in ('clock','reset'): conn[name]=name
            elif name.startswith(('taskIn_','taskOut_','taskOutGlobal_','closureIn_','sendArgument_')):
                target=f'BindTo_PE_{taskname}_{pe}_{name}'
                if target in hp:
                    if hp[target][1] != spec[1] or hp[target][0] == spec[0]:
                        raise ValueError(f'Incompatible stream port {target}: {spec}, {hp[target]}')
                    conn[name]=target
                elif name.startswith('taskIn_'):
                    raise ValueError(f'Missing required task input: {target}')
                elif spec[0]=='input': conn[name]="'0"
                else: conn[name]=''
            else:
                new=label+'_'+name; external[new]=spec;conn[name]=new
        body.append(prefix+module+' '+label+'(\n'+',\n'.join(f'.{k}({v})' for k,v in conn.items())+'\n);')
    body.append(hcname+' runtime(\n'+',\n'.join(f'.{k}({v})' for k,v in hconn.items())+'\n);')
    (out/'System.sv').write_text('module System(\n'+',\n'.join(decl(k,v) for k,v in external.items())+'\n);\n'+
        '\n'.join(decl(k,v,True)+';' for k,v in internal.items())+'\n'+'\n'.join(body)+'\nendmodule\n')
    out=root/'src/host/generated/include'
    # Normalize raw AXI names and typed CPU AXI names into the same C++ adapter.
    def signal(prefix,field):
        raw=prefix+'_'+field
        if raw in external:return raw
        for ch in ('ar','aw','r','w','b'):
            if field.lower().startswith(ch):
                rest=field[len(ch):].lower();typed=prefix+'_'+ch+('_' if rest in ('valid','ready') else '_bits_')+rest
                if typed in external:return typed
        raise KeyError((prefix,field))
    bindings=['// Generated from the elaborated RTL, do not edit.']
    mems=sorted(set(re.match(r'(m_axi_\d+)_',x)[1] for x in external if re.match(r'm_axi_\d+_',x)))
    mems += [c[0]+'_m_axi' for c in cpus+readers]
    for i,prefix in enumerate(mems):
        def s(f): return signal(prefix,f)
        width=external[s('WDATA')][1]; read=prefix+'_ARVALID' in external or prefix+'_ar_valid' in external
        bindings.append(f'addMemory({width//8}, "{prefix}", [this](MemoryPort& p, bool drive) {{')
        bindings.append('if(drive) {')
        for field,expr in [('AWREADY','p.awReady'),('WREADY','p.wReady'),('BVALID','p.bValid'),('BID','p.bId'),('BRESP','p.bResp')]:
            
            if field.endswith('ID') and prefix.startswith('m_axi_') and prefix+'_'+field not in external: continue
            bindings.append(f'dut.{s(field)}={expr};')
        if read:
            for field,expr in [('ARREADY','p.arReady'),('RVALID','p.rValid'),('RID','p.rId'),('RRESP','0'),('RLAST','p.rLast')]:
                if field == 'RID' and prefix.startswith('m_axi_') and prefix+'_'+field not in external: continue
                bindings.append(f'dut.{s(field)}={expr};')
            bindings.append(f'putData(dut.{s("RDATA")},p.rData);')
        bindings.append('} else {')
        for field,member in [('AWVALID','awValid'),('AWADDR','awAddress'),('AWID','awId'),('AWLEN','awLength'),('AWSIZE','awSize'),('AWBURST','awBurst'),('WVALID','wValid'),('WLAST','wLast'),('BREADY','bReady')]:
            if field.endswith('ID') and prefix.startswith('m_axi_') and prefix+'_'+field not in external: continue
            bindings.append(f'p.{member}=dut.{s(field)};')
        bindings.append(f'p.wData=getData(dut.{s("WDATA")},{width//8});')
        bindings.append(f'p.wStrobe=getData(dut.{s("WSTRB")},{(width//8+7)//8});')
        if read:
            for field,member in [('ARVALID','arValid'),('ARADDR','arAddress'),('ARID','arId'),('ARLEN','arLength'),('ARSIZE','arSize'),('ARBURST','arBurst'),('RREADY','rReady')]:
                if field.endswith('ID') and prefix.startswith('m_axi_') and prefix+'_'+field not in external: continue
                bindings.append(f'p.{member}=dut.{s(field)};')
        bindings.append('}});')
    for prefix in ['s_axil_mgmt_hardcilk']+[c[0]+'_s_axil' for c in cpus]:
        def s(f):return signal(prefix,f)
        bindings.append(f'controls.emplace_back([this](LitePort& p, bool drive) {{ if(drive) {{')
        for field,member in [('AWADDR','address'),('AWVALID','awValid'),('WDATA','data'),('WSTRB','strobe'),('WVALID','wValid'),('BREADY','bReady'),('ARADDR','address'),('ARVALID','arValid'),('RREADY','rReady')]:bindings.append(f'dut.{s(field)}=p.{member};')
        bindings.append('} else {')
        for field,member in [('AWREADY','awReady'),('WREADY','wReady'),('BVALID','bValid'),('BRESP','bResp'),('ARREADY','arReady'),('RVALID','rValid'),('RDATA','rData'),('RRESP','rResp')]:bindings.append(f'p.{member}=dut.{s(field)};')
        bindings.append('}}, [this] { tick(); });')
    (out/'bindings.inc').write_text('\n'.join(bindings))
    checks=['if(dut.cpuInterfaceFault) throw std::runtime_error("HardCilk stream protocol fault");']
    checks += [f'if(dut.{c[0]}_fault) throw std::runtime_error("{c[0]} trapped");' for c in cpus]
    checks += [f'if(dut.{c[0]}_fault) throw std::runtime_error("{c[0]} read error");' for c in readers]
    (out/'checks.inc').write_text('\n'.join(checks))
    descriptions=[]
    byname={task['name']:task for task in project['tasks']}
    for i,(label,task,pe) in enumerate(cpus):
        cfg=byname[task['name']]
        descriptions.append(f'cpus.push_back({{"{task["name"]}", {pe}, {i+1}, {cfg["taskBeats"]}, {cfg["slabBytes"]}, {cfg["numThreads"]}}});')
    for task in arch['taskDescriptors']:
        sides={s['sideType']:s for s in task['sidesConfigs']}
        for n,reg in enumerate(task['mgmtBaseAddresses']['schedulerServersBaseAddresses']):
            descriptions.append(f'queues.push_back({{"{task["name"]}", {reg}, {task["widthTask"]//8}, {sides["scheduler"]["capacityVirtualQueue"]}, {str(task["isRoot"] and n==0).lower()}}});')
        for reg in task['mgmtBaseAddresses']['allocationServersBaseAddresses']:
            descriptions.append(f'allocators.push_back({{{reg}, {task["allocatorSlabBytes"]}, {sides["allocator"]["capacityVirtualQueue"]}}});')
    (out/'descriptors.inc').write_text('\n'.join(descriptions))
    # Machine-readable external port boundary for board-specific integration.
    (root/'src/rtl/system/ports.json').write_text(json.dumps(external,indent=2))
    print(f'Composed {len(cpus)} CPUs, {len(readers)} memory readers and {hcname}, {len(mems)} AXI masters')

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('project',type=Path);compose(ap.parse_args().project)
