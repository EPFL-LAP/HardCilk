#ifndef SYSC_NETW_LINK_INCLUDED
#define SYSC_NETW_LINK_INCLUDED

#include <memory>
#include <random>

#include <systemc>

#include "Packet.hpp"

namespace sysc_netw {

namespace detail {

using namespace sc_core;

struct Link : sc_module {
    Link(
        sc_module_name const& name,
        sc_time const& period,
        sc_time const& delay
    )
        : sc_module { name }
        , SC_NAMED(source)
        , SC_NAMED(sink)
        , SC_NAMED(clock_, period)
        , delay_ { delay } {

        SC_THREAD(run);
    }

    sc_port<sc_fifo_in_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND> source;
    sc_port<sc_fifo_out_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND> sink;

private:
    sc_clock clock_;
    sc_time delay_;

    void run() {
        while (true) {
            if (source->num_available() && sink->num_free()) {
                auto packet = source->read();

                sc_spawn([this, packet] {
                    wait(delay_);
                    sink->write(packet);
                });
            }

            wait(clock_.posedge_event());
        }
    }
};

} // namespace detail

using detail::Link;

} // namespace sysc_netw

#endif // SYSC_NETW_LINK_INCLUDED
