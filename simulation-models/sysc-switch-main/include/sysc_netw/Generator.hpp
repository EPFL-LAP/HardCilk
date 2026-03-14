#ifndef SYSC_NETW_GENERATOR_INCLUDED
#define SYSC_NETW_GENERATOR_INCLUDED

#include <memory>

#include <systemc>

#include "Packet.hpp"

namespace sysc_netw {

namespace detail {

using namespace sc_core;

struct Generator : sc_module {
    Generator(const sc_module_name& name)
        : sc_module { name }
        , SC_NAMED(sink) {
    }

    void send(Packet::ptr packet_ptr) {
        packet_ptr->time1 = sc_time_stamp();
        packet_ptr->src = this;
        packet_ptr->prevRoute.push_back({ .component = this, .time1 = SC_ZERO_TIME, .time2 = packet_ptr->time1, .localDestIndex = 0 });

        sink->write(packet_ptr);
    }

    sc_port<sc_fifo_out_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND> sink;
};

} // namespace detail

using detail::Generator;

} // namespace sysc_netw

#endif // SYSC_NETW_GENERATOR_INCLUDED
