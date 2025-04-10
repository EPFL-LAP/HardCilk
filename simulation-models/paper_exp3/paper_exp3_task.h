#ifndef __paper_exp3_task_HPP__
#define __paper_exp3_task_HPP__

#include <fmt/core.h>

#include <algorithm>
#include <memory>
#include <random>
#include <vector>

#include <cassert>

#include <chext_test/elastic/Driver.hpp>
#include <systemc>
#include <Protocols.hpp>
#include <unordered_set>


#define clockPeriod_ns 2


extern int nodesProcessed;
extern double T1;
extern int remainingTasks;

extern int tasksSpawnedNext;
extern int tasksNotifiedFromA;
extern int tasksNotifiedFromB;
extern int tasksSpawned;


extern std::unordered_set <uint32_t> uniqueTags;
extern int uniqueTagsCount;

extern uint32_t _exp3_serialTasks;

struct task_INPE {
  uint32_t counter;
  uint32_t depth;
  uint32_t delay;
  uint32_t branchFactor;
  uint32_t tag;
  uint32_t index;
  uint64_t cont;
};

// Define edgeMap_spawn_next for fib to spawn next new tasks
struct task_spawn_next {
  uint64_t addr;
  task_INPE  data;
  uint32_t size;
  uint32_t allow;
  uint8_t __padding[16];
};



using namespace sc_core;
using namespace sc_dt;

struct paper_exp3_task : sc_core::sc_module {
  paper_exp3_task(
      sc_module_name const &name,
      chext_test::elastic::detail::Source<sc_core::sc_signal<sc_dt::sc_bv<64>>> *argOut,
      chext_test::elastic::detail::Sink<sc_core::sc_signal<sc_dt::sc_bv<64>>> *closureIn,
      chext_test::elastic::detail::Source<sc_core::sc_signal<sc_dt::sc_bv<512>>> *spawnNext,
      chext_test::elastic::detail::Source<sc_core::sc_signal<sc_dt::sc_bv<256>>> *taskOutSynced,
      chext_test::elastic::detail::Source<sc_core::sc_signal<sc_dt::sc_bv<256>>> *taskOut,
      chext_test::elastic::detail::Sink<sc_core::sc_signal<sc_dt::sc_bv<256>>> *taskIn
        )
      : sc_module(name), 
        taskIn_(taskIn),
        taskOut_(taskOut),
        taskOutSynced_(taskOutSynced),
        spawnNext_(spawnNext),
        argOut_(argOut),
        closureIn_(closureIn) {

    SC_THREAD(executer_thread);
    SC_THREAD(spawner_thread);
  }


private:

  chext_test::elastic::Sink<sc_core::sc_signal<sc_dt::sc_bv<256>>> *taskIn_;
  chext_test::elastic::Source<sc_core::sc_signal<sc_dt::sc_bv<256>>> *taskOut_;
  chext_test::elastic::Source<sc_core::sc_signal<sc_dt::sc_bv<256>>> *taskOutSynced_;
  chext_test::elastic::Source<sc_core::sc_signal<sc_dt::sc_bv<512>>>* spawnNext_;
  chext_test::elastic::Source<sc_core::sc_signal<sc_dt::sc_bv<64>>> *argOut_;
  chext_test::elastic::Sink<sc_core::sc_signal<sc_dt::sc_bv<64>>> *closureIn_;


  sc_core::sc_fifo<sc_dt::sc_bv<256>> fifo_;

  void executer_thread() {
    while (true) {
      auto receievedPacket = taskIn_->receive();

      // Conver the task to a struct of type task from the sc_bv<128> task
      task_INPE t;
      t.counter = receievedPacket.range(31, 0).to_uint();
      t.depth = receievedPacket.range(63, 32).to_uint();
      t.delay = receievedPacket.range(95, 64).to_uint();
      t.branchFactor = receievedPacket.range(127, 96).to_uint();
      t.tag = receievedPacket.range(159, 128).to_uint();
      t.index = receievedPacket.range(191, 160).to_uint();
      t.cont = receievedPacket.range(255, 192).to_ulong();

      //bool returningTask = ((t.cont & 0x3FFFFFFFF) != 0x3FFFFFFFF);
    

      // Check if the tag is unique
      if(uniqueTags.find(t.tag) != uniqueTags.end()){
        std::cout << "Tag already exists: " << t.tag << std::endl;

        //Print task info
        std::cout << "Task info: " << std::endl;
        std::cout << "Tag: " << t.tag << std::endl;
        std::cout << "Counter: " << t.counter << std::endl;
        std::cout << "Depth: " << t.depth << std::endl;
        std::cout << "Delay: " << t.delay << std::endl;
        std::cout << "Branch Factor: " << t.branchFactor << std::endl;
        std::cout << "Index: " << t.index << std::endl;
        std::cout << "Cont: " << t.cont << std::endl;
        

        sc_stop();
      } else {
        uniqueTags.insert(t.tag);
      }


      if (t.depth == 0) {
        wait(sc_core::sc_time(clockPeriod_ns * t.delay, sc_core::SC_NS));
        T1 += sc_core::sc_time(clockPeriod_ns * t.delay, sc_core::SC_NS).to_seconds();
        if(t.cont != 0){
          // make t.cont a sc_bv<64>
          sc_dt::sc_bv<64> t_cont;
          t_cont.range(63, 0) = t.cont;
          argOut_->send(t_cont);
          tasksNotifiedFromA += 1;
        }
        nodesProcessed++;
      } else {
        bool continuation = false;
        for (std::uint32_t i = t.index; i < t.branchFactor; ++i) {
          wait(sc_core::sc_time(clockPeriod_ns * t.delay, sc_core::SC_NS));
          T1 += sc_core::sc_time(clockPeriod_ns * t.delay, sc_core::SC_NS).to_seconds();
          if(i < _exp3_serialTasks){
            continuation = true;
            remainingTasks+=2;
            auto addr = closureIn_->receive().range(63, 0).to_ulong();
            
            task_INPE new_cont_task;
            new_cont_task.tag = uniqueTagsCount++;
            new_cont_task.counter = 1;
            new_cont_task.branchFactor = t.branchFactor;
            new_cont_task.depth = t.depth;
            new_cont_task.cont = t.cont;
            new_cont_task.index = i + 1;  
            new_cont_task.delay = t.delay;
  

            task_INPE new_task;
            new_task.tag = uniqueTagsCount++;
            new_task.counter = 0;
            new_task.branchFactor = t.branchFactor;
            new_task.depth = t.depth - 1;
            new_task.cont = addr;
            new_task.index = 0;  
            new_task.delay = t.delay;

            task_spawn_next sn;
            sn.addr = addr & 0x3FFFFFFFF;
            sn.data = new_cont_task;
            sn.size = 5; // task size is 2^5 bytes
            sn.allow = 1;
            
            

            // convert sn to sc_bv<512>
            sc_dt::sc_bv<512> spawnNext;

            spawnNext.range(63, 0) = sn.addr;
            spawnNext.range(95, 64) = sn.data.counter; 
            spawnNext.range(127, 96) = sn.data.depth;
            spawnNext.range(159, 128) = sn.data.delay;
            spawnNext.range(191, 160) = sn.data.branchFactor;
            spawnNext.range(223, 192) = sn.data.tag;
            spawnNext.range(255, 224) = sn.data.index;
            spawnNext.range(319, 256) = sn.data.cont;
            spawnNext.range(351, 320) = sn.size;
            spawnNext.range(383, 352) = sn.allow;

            
            spawnNext_->send(spawnNext);
            tasksSpawnedNext += 1;
            

            // Convert new_task to sc_bv<256>
            sc_dt::sc_bv<256> task;
            task.range(31, 0) = new_task.delay;
            task.range(63, 32) = new_task.depth;
            task.range(95, 64) = new_task.delay;
            task.range(127, 96) = new_task.branchFactor;
            task.range(159, 128) = new_task.tag;
            task.range(191, 160) = new_task.index;
            task.range(255, 192) = new_task.cont;

            //std::cout << "Task should return to: " << task.range(255, 192).to_uint() << std::endl;

            taskOutSynced_->send(task);
            tasksSpawned++;

            break;
  
          } else{
            remainingTasks++;
            task_INPE new_task;
            new_task.tag = uniqueTagsCount++;
            new_task.counter = 0;
            new_task.branchFactor = t.branchFactor;
            new_task.depth = t.depth - 1;
            new_task.cont = 0;
            new_task.index = 0;  
            new_task.delay = t.delay;
            // Convert new_task to sc_bv<128>
            sc_dt::sc_bv<256> task;
            task.range(31, 0) = new_task.delay;
            task.range(63, 32) = new_task.depth;
            task.range(95, 64) = new_task.delay;
            task.range(127, 96) = new_task.branchFactor;
            task.range(159, 128) = new_task.tag;
            task.range(191, 160) = new_task.index;
            task.range(255, 192) = new_task.cont;
            
            fifo_.write(task);
            tasksSpawned++;
          }
        }
        if (!continuation) {
          if (t.cont != 0) {
            // make t.cont a sc_bv<64>
            sc_dt::sc_bv<64> t_cont;
            t_cont.range(63, 0) = t.cont;
            argOut_->send(t_cont);
            tasksNotifiedFromB += 1;
          }
          nodesProcessed++;
        }
      }

      --remainingTasks;


      assert(nodesProcessed < (tasksSpawned + tasksNotifiedFromA + tasksNotifiedFromB + 6));

      //if(nodesProcessed + 6 > (tasksSpawned + ))

    }
  }

  void spawner_thread() {
    while (true) {
      taskOut_->send(fifo_.read());
    }
  }

};

#endif // __paper_exp3_task_HPP__
