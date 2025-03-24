#ifndef __paper_exp2_task1_HPP__
#define __paper_exp2_task1_HPP__

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


extern double T1;
extern int remainingTasks;


struct task_1_INPE1 {
  uint32_t delay;
};

using namespace sc_core;
using namespace sc_dt;

struct paper_exp2_task1 : sc_core::sc_module {
  paper_exp2_task1(
      sc_module_name const &name,
      chext_test::elastic::detail::Sink<sc_core::sc_signal<sc_dt::sc_bv<32>>> *taskIn
        )
      : sc_module(name), 
        taskIn_(taskIn){

    SC_THREAD(executer_thread);
  }


private:

  chext_test::elastic::Sink<sc_core::sc_signal<sc_dt::sc_bv<32>>> *taskIn_;



  void executer_thread() {
    while (true) {
      auto receievedPacket = taskIn_->receive();

      // Conver the task to a struct of type task from the sc_bv<128> task
      task_1_INPE1 t;
      t.delay = receievedPacket.range(31, 0).to_uint();

      wait(sc_core::sc_time(clockPeriod_ns * t.delay / 2, sc_core::SC_NS));
      T1 += sc_core::sc_time(clockPeriod_ns * t.delay / 2, sc_core::SC_NS).to_seconds();

      --remainingTasks;
    }
  }



};

#endif // __paper_exp2_task1_HPP__
