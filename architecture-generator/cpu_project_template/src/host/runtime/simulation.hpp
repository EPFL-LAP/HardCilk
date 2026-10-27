#pragma once
#include "VSystem.h"
#include <verilated.h>
#include "hardcilk_boot.hpp"
#include <algorithm>
#include <array>
#include <cstring>
#include <deque>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <random>
#include <string>
#include <type_traits>

using Bytes = std::vector<std::uint8_t>;
static void check(bool ok, const std::string& message) { if (!ok) throw std::runtime_error(message); }
template<class T> Bytes getData(const T& value, unsigned bytes) {
    Bytes out(bytes); std::memcpy(out.data(), &value, bytes); return out;
}
template<class T> void putData(T& value, const Bytes& bytes) {
    std::memset(&value, 0, sizeof(value));
    if (!bytes.empty()) std::memcpy(&value, bytes.data(), std::min(bytes.size(), sizeof(value)));
}
struct LitePort {
    std::uint64_t address=0,data=0,strobe=0,rData=0;
    bool awValid=0,wValid=0,bReady=0,arValid=0,rReady=0;
    bool awReady=0,wReady=0,bValid=0,arReady=0,rValid=0;
    unsigned bResp=0,rResp=0;
};
struct Control {
    LitePort p;
    std::function<void(LitePort&,bool)> bind;
    std::function<void()> tick;
    Control(decltype(bind) b, decltype(tick) t):bind(b),tick(t){}
    void write(std::uint64_t address,std::uint64_t data,std::uint64_t strobe) {
        p.address=address;p.data=data;p.strobe=strobe;p.awValid=p.wValid=p.bReady=true;
        bool aw=false,w=false;
        for(unsigned i=0;i<10000;++i) {
            tick();
            if(p.awValid&&p.awReady){aw=true;p.awValid=false;}
            if(p.wValid&&p.wReady){w=true;p.wValid=false;}
            if(p.bValid) {check(aw&&w,"premature AXI-Lite B");p.bReady=false;check(!p.bResp,"AXI-Lite write error");return;}
        }
        throw std::runtime_error("AXI-Lite write timeout at "+std::to_string(address));
    }
    std::uint64_t read(std::uint64_t address) {
        p.address=address;p.arValid=true;p.rReady=true;
        bool ar=false;
        for(unsigned i=0;i<10000;++i) {
            tick();
            if(p.arValid&&p.arReady){ar=true;p.arValid=false;}
            if(p.rValid){check(ar,"premature AXI-Lite R");p.rReady=false;check(!p.rResp,"AXI-Lite read error");return p.rData;}
        }
        throw std::runtime_error("AXI-Lite read timeout at "+std::to_string(address));
    }
    void write32(std::uint32_t a,std::uint32_t v){write(a,v,15);}
    std::uint32_t read32(std::uint32_t a){return read(a);}
    void write64(std::uint32_t a,std::uint64_t v){write(a,v,255);}
};
struct MemoryPort {
    std::uint64_t awAddress=0,arAddress=0;
    Bytes wStrobe;
    unsigned awId=0,awLength=0,awSize=0,awBurst=0,arId=0,arLength=0,arSize=0,arBurst=0;
    unsigned bId=0,rId=0,bResp=0;
    bool awValid=0,awReady=0,wValid=0,wReady=0,wLast=0,bValid=0,bReady=0;
    bool arValid=0,arReady=0,rValid=0,rReady=0,rLast=0;
    Bytes wData,rData;
};
struct MemoryModel {
    struct Address {std::uint64_t address;unsigned id,length,size,beat=0;};
    struct Write {Bytes data,strobe;bool last;};
    struct Response {unsigned id;std::uint64_t due;unsigned resp;};
    struct Read {unsigned id;std::uint64_t due;Bytes data;bool last;};
    MemoryPort p;
    unsigned width;
    std::string name;
    std::function<void(MemoryPort&,bool)> bind;
    std::deque<Address> addresses;
    std::deque<Write> writes;
    std::deque<Response> responses;
    std::deque<Read> reads;
    std::uint64_t writeCount=0,readCount=0;
    bool failNextWrite=false;
    MemoryModel(unsigned w,std::string n,decltype(bind) b):width(w),name(n),bind(b){}
    void clear(){addresses.clear();writes.clear();responses.clear();reads.clear();p={};writeCount=readCount=0;failNextWrite=false;}
    void drive(std::uint64_t cycle,std::mt19937& rng){
        p.awReady=addresses.size()<16 && rng()%4!=0;
        p.wReady=writes.size()<16 && rng()%3!=0;
        p.arReady=reads.size()<64 && rng()%5!=0;
        p.bValid=!responses.empty()&&responses.front().due<=cycle;
        p.bId=p.bValid?responses.front().id:0;
        p.bResp=p.bValid?responses.front().resp:0;
        p.rValid=!reads.empty()&&reads.front().due<=cycle;
        p.rId=p.rValid?reads.front().id:0;p.rLast=p.rValid&&reads.front().last;
        p.rData=p.rValid?reads.front().data:Bytes(width);
        bind(p,true);
    }
    void sample(){bind(p,false);}
    void commit(Bytes& memory,std::uint64_t cycle,std::mt19937& rng){
        if(p.bValid&&p.bReady)responses.pop_front();
        if(p.rValid&&p.rReady)reads.pop_front();
        if(p.awValid&&p.awReady){
            check(p.awBurst==1,"Only INCR writes supported: "+name);
            addresses.push_back({p.awAddress,p.awId,p.awLength,p.awSize});
        }
        if(p.wValid&&p.wReady)writes.push_back({p.wData,p.wStrobe,p.wLast});
        if(!addresses.empty()&&!writes.empty()){
            auto& a=addresses.front();auto& w=writes.front();
            check((1U<<a.size)<=width,"Invalid AWSIZE");
            auto address=a.address+(std::uint64_t(a.beat)<<a.size);
            auto base=address&~std::uint64_t(width-1);
            check(base+width<=memory.size(),"Write outside simulated memory: "+name);
            check(w.last==(a.beat==a.length),"WLAST mismatch: "+name);
            for(unsigned i=0;i<width;++i)if((w.strobe.at(i/8)>>(i%8))&1)memory[base+i]=w.data[i];
            ++writeCount;
            writes.pop_front();
            if(a.beat++==a.length){responses.push_back({a.id,cycle+2+rng()%29,failNextWrite?2U:0U});failNextWrite=false;addresses.pop_front();}
        }
        if(p.arValid&&p.arReady){
            check(p.arBurst==1,"Only INCR reads supported: "+name);
            check((1U<<p.arSize)<=width,"Invalid ARSIZE");
            for(unsigned i=0;i<=p.arLength;++i){
                auto address=(p.arAddress+(std::uint64_t(i)<<p.arSize))&~std::uint64_t(width-1);
                check(address+width<=memory.size(),"Read outside simulated memory: "+name);
                Bytes data(memory.begin()+address,memory.begin()+address+width);
                reads.push_back({p.arId,cycle+2+rng()%19,std::move(data),i==p.arLength});++readCount;
            }
        }
    }
};
inline std::vector<std::uint32_t> loadImage(const std::string& name){
    std::ifstream f(name,std::ios::binary);check(bool(f),"Cannot read "+name);
    Bytes data((std::istreambuf_iterator<char>(f)),{});check(data.size()%4==0,"Unaligned image");
    std::vector<std::uint32_t> words(data.size()/4);std::memcpy(words.data(),data.data(),data.size());return words;
}

struct CpuSimulation {
    struct Cpu {std::string task; unsigned pe,control,taskBeats,slabBytes,threads;};
    struct Queue {std::string task;unsigned control,bytes,capacity;bool root;};
    struct Allocator {unsigned control,slabBytes,capacity;};
    struct RootTask {std::string task; Bytes data;};
    VSystem dut;
    Bytes memory;
    std::vector<std::unique_ptr<MemoryModel>> memories;
    std::vector<Control> controls;
    std::vector<Cpu> cpus;
    std::vector<Queue> queues;
    std::vector<Allocator> allocators;
    std::vector<unsigned> serversToStart;
    std::mt19937 rng;
    std::uint64_t cycle=0, nextAddress=0x10000;
    bool doneSeen=false,checking=false;
    std::string directory;
    CpuSimulation(unsigned seed, std::string project, std::size_t memoryBytes=16*1024*1024)
        :memory(memoryBytes),rng(seed),directory(project){
#include "bindings.inc"
#include "descriptors.inc"
    }
    void addMemory(unsigned w,std::string n,std::function<void(MemoryPort&,bool)> bind){memories.emplace_back(new MemoryModel(w,n,bind));}
    void tick(){
        dut.clock=0;
        for(auto& c:controls)c.bind(c.p,true);
        for(auto& m:memories)m->drive(cycle,rng);
        dut.eval();
        for(auto& c:controls)c.bind(c.p,false);
        for(auto& m:memories)m->sample();
        dut.clock=1;dut.eval();
        if(!dut.reset){for(auto& m:memories)m->commit(memory,cycle,rng);doneSeen|=dut.done;}
        ++cycle;
        if(checking){
#include "checks.inc"
        }
    }
    std::uint64_t allocate(std::size_t bytes, std::size_t alignment=32){
        check(alignment && !(alignment&(alignment-1)),"Invalid memory alignment");
        auto base=(nextAddress+alignment-1)&~std::uint64_t(alignment-1);
        check(base<=memory.size() && bytes<=memory.size()-base,"Simulated memory exhausted");
        nextAddress=base+bytes;return base;
    }
    void word(std::uint64_t address,std::uint64_t value,unsigned bytes=8){for(unsigned i=0;i<bytes;++i)memory.at(address+i)=value>>(8*i);}
    std::uint32_t word32(std::uint64_t address){std::uint32_t v=0;for(unsigned i=0;i<4;++i)v|=std::uint32_t(memory.at(address+i))<<(8*i);return v;}
    void reset(){
        checking=false;doneSeen=false;serversToStart.clear();nextAddress=0x10000;
        std::fill(memory.begin(),memory.end(),0);for(auto& m:memories)m->clear();
        for(auto& c:controls)c.p={};
        dut.reset=1;for(unsigned i=0;i<8;++i)tick();dut.reset=0;for(unsigned i=0;i<8;++i)tick();
        doneSeen=false;
    }
    void initialize(const std::vector<RootTask>& roots,unsigned grantsPerAllocator=32){
        auto& control=controls.at(0);
        for(const auto& root:roots)
            check(std::any_of(queues.begin(),queues.end(),[&](const auto& q){return q.root && q.task==root.task;}),"Unknown root task type: "+root.task);
        for(const auto& q:queues){
            check(control.read(q.control)!=0,"Scheduler must be paused before initialization");
            auto base=allocate(std::size_t(q.bytes)*q.capacity,512);
            unsigned count=0;
            if(q.root)for(const auto& root:roots)if(root.task==q.task){
                check(root.data.size()==q.bytes && count<q.capacity,"Invalid root task payload/queue capacity");
                std::copy(root.data.begin(),root.data.end(),memory.begin()+base+count*q.bytes);++count;
            }
            control.write64(q.control+8,base);control.write64(q.control+16,q.capacity);
            control.write64(q.control+24,count);control.write64(q.control+32,0);control.write64(q.control+48,count);
            check(control.read(q.control+8)==base,"Scheduler base readback mismatch");
            serversToStart.push_back(q.control);
        }
        for(const auto& a:allocators){
            check(control.read(a.control)!=0,"Allocator must be paused before initialization");
            check(a.slabBytes && grantsPerAllocator && grantsPerAllocator%16==0 && grantsPerAllocator<=a.capacity,"Invalid slab grant count");
            auto base=allocate(std::size_t(grantsPerAllocator)*a.slabBytes,a.slabBytes);
            finegrained::SlabPool pool(base,std::size_t(grantsPerAllocator)*a.slabBytes,a.slabBytes);
            auto grants=pool.grant(grantsPerAllocator);auto fifo=allocate(grants.size()*8,512);
            for(unsigned i=0;i<grants.size();++i)word(fifo+i*8,grants[i]);
            control.write64(a.control+8,fifo);control.write64(a.control+16,grants.size());
            check(control.read(a.control+8)==fifo,"Allocator base readback mismatch");
            serversToStart.push_back(a.control);
        }
    }
    void boot(){
        for(const auto& cpu:cpus)
            finegrained::bootHardCilkCpu(controls.at(cpu.control),
              loadImage(directory+"/build/firmware/"+cpu.task+".bin"),cpu.taskBeats,cpu.slabBytes);
    }
    void start(){
        for(auto reg:serversToStart)controls.at(0).write64(reg,0);
        checking=true;
    }
    void runUntilDone(std::uint64_t limit=10000000){
        auto deadline=cycle+limit;
        while(!doneSeen && cycle<deadline)tick();
        check(doneSeen,"CPU application timed out");
        for(unsigned i=0;i<200;++i)tick();
    }
};
