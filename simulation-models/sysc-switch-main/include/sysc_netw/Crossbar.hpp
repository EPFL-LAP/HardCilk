#ifndef SYSC_NETW_CROSSBAR_INCLUDED
#define SYSC_NETW_CROSSBAR_INCLUDED

#include <memory>
#include <random>

#include <systemc>

#include "Arbiter.hpp"
#include "Packet.hpp"

namespace sysc_netw {

namespace detail {

using namespace sc_core;

struct Crossbar : sc_module {
    Crossbar(const sc_module_name& name, unsigned n, sc_time const& period)
        : sc_module { name }
        , n_ { n }
        , SC_NAMED(clock_, period)
        , sources_(n)
        , sinks_(n)
        , fifos_(n * n) {

        for (unsigned src_idx = 0; src_idx < n; ++src_idx) {
            sc_spawn([this, src_idx] { sourceProcess(src_idx); });
        }

        for (unsigned dest_idx = 0; dest_idx < n; ++dest_idx) {
            sc_spawn([this, dest_idx] { sinkProcess(dest_idx); });
        }
    }

    sc_port_b<sc_fifo_in_if<Packet::ptr>>& source_at(unsigned idx) {
        return sources_.at(idx);
    }

    sc_port_b<sc_fifo_out_if<Packet::ptr>>& sink_at(unsigned idx) {
        return sinks_.at(idx);
    }

private:
    unsigned n_;
    sc_clock clock_;

    std::vector<sc_port<sc_fifo_in_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND>> sources_;
    std::vector<sc_port<sc_fifo_out_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND>> sinks_;

    std::vector<sc_fifo<Packet::ptr>> fifos_;

    sc_fifo<Packet::ptr>& get_fifo(unsigned src_idx, unsigned dest_idx) {
        return fifos_[src_idx * n_ + dest_idx];
    }

    void sourceProcess(unsigned src_idx) {
        if (sources_[src_idx].size() == 0)
            return;

        while (true) {
            auto packet_ptr = sources_[src_idx]->read();
            auto thisHop = packet_ptr->nextRoute.front();
            packet_ptr->nextRoute.pop_front();

            thisHop.time1 = sc_time_stamp();
            packet_ptr->prevRoute.push_back(thisHop);

            get_fifo(src_idx, thisHop.localDestIndex).write(packet_ptr);
        }
    }

    void sinkProcess(unsigned dest_idx) {
        if (sinks_[dest_idx].size() == 0)
            return;

        PriorityArbiter arbiter(n_);

        while (true) {
            for (unsigned src_idx = 0; src_idx < n_; ++src_idx)
                arbiter.valid(src_idx, get_fifo(src_idx, dest_idx).num_available() > 0);

            int chosen_src_idx = -1;

            if (sinks_[dest_idx]->num_free() > 0 && (chosen_src_idx = arbiter.choose()) >= 0) {
                auto packet_ptr = get_fifo(chosen_src_idx, dest_idx).read();
                packet_ptr->prevRoute.back().time2 = sc_time_stamp();

                sinks_[dest_idx]->write(packet_ptr);
            }

            wait(clock_.posedge_event());
        }
    }
};

} // namespace detail

using detail::Crossbar;

} // namespace sysc_netw

#endif // SYSC_NETW_CROSSBAR_INCLUDED
