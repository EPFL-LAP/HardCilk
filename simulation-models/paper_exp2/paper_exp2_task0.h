#ifndef __paper_exp2_task0_HPP__
#define __paper_exp2_task0_HPP__

#include <fmt/core.h>

#include <algorithm>
#include <memory>
#include <random>
#include <vector>

#include <cassert>

#include <chext_test/elastic/Driver.hpp>
#include <systemc>
#include <Protocols.hpp>


#define clockPeriod_ns 2

extern int nodesProcessed;
extern double T1;
extern int remainingTasks;


struct task0_INPE {
  uint32_t delay;
  uint32_t depth;
  uint32_t branchFactor;
  uint32_t cont; // fake cont
};

struct task_1_INPE {
  uint32_t delay;
};

using namespace sc_core;
using namespace sc_dt;

struct paper_exp2_task0 : sc_core::sc_module {
  paper_exp2_task0(
      sc_module_name const &name,
      chext_test::elastic::detail::Source<sc_core::sc_signal<sc_dt::sc_bv<128>>> *taskOut,
      chext_test::elastic::detail::Sink<sc_core::sc_signal<sc_dt::sc_bv<128>>> *taskIn,
      chext_test::elastic::detail::Source<sc_core::sc_signal<sc_dt::sc_bv<32>>> *taskOutGlobal
        )
      : sc_module(name), 
        taskIn_(taskIn),
        taskOut_(taskOut),
        taskOutGlobal_(taskOutGlobal) {

    SC_THREAD(executer_thread);
    SC_THREAD(spawner_thread);
    SC_THREAD(spawner_thread_task1);
  }


private:

  chext_test::elastic::Sink<sc_core::sc_signal<sc_dt::sc_bv<128>>> *taskIn_;
  chext_test::elastic::Source<sc_core::sc_signal<sc_dt::sc_bv<128>>> *taskOut_;
  chext_test::elastic::Source<sc_core::sc_signal<sc_dt::sc_bv<32>>> *taskOutGlobal_;

  sc_core::sc_fifo<sc_dt::sc_bv<128>> fifo_;

  sc_core::sc_fifo<sc_dt::sc_bv<32>> fifo_task1_;

  void executer_thread() {
    while (true) {
      auto receievedPacket = taskIn_->receive();
      nodesProcessed++;
      // Conver the task to a struct of type task from the sc_bv<128> task
      task0_INPE t;
      t.delay = receievedPacket.range(31, 0).to_uint();
      t.depth = receievedPacket.range(63, 32).to_uint();
      t.branchFactor = receievedPacket.range(95, 64).to_uint();
      t.cont = receievedPacket.range(127, 96).to_uint();

      if (t.depth == 0) {
        wait(sc_core::sc_time(clockPeriod_ns * t.delay / 2, sc_core::SC_NS));
        T1 += sc_core::sc_time(clockPeriod_ns * t.delay / 2, sc_core::SC_NS).to_seconds();
      } else {

        for (std::uint32_t i = 0; i < t.branchFactor; ++i) {
          wait(sc_core::sc_time(clockPeriod_ns * t.delay / 2, sc_core::SC_NS));
          T1 += sc_core::sc_time(clockPeriod_ns * t.delay / 2, sc_core::SC_NS).to_seconds();
          remainingTasks += 2;
          
          sc_dt::sc_bv<128> task;
          task.range(31, 0) = t.delay;
          task.range(63, 32) = t.depth - 1;
          task.range(95, 64) = t.branchFactor;
          task.range(127, 96) = t.cont;
          fifo_.write(task);

          sc_dt::sc_bv<32> task1;
          task1.range(31, 0) = t.delay;
          fifo_task1_.write(task1);
        }
      }
      --remainingTasks;
    }
  }

  void spawner_thread() {
    while (true) {
      taskOut_->send(fifo_.read());
    }
  }

  void spawner_thread_task1() {
    while (true) {
      taskOutGlobal_->send(fifo_task1_.read());
    }
  }

};

#endif // __paper_exp2_task0_HPP__
