#ifndef SYSC_NETW_CONSUMER_INCLUDED
#define SYSC_NETW_CONSUMER_INCLUDED

#include <memory>

#include <systemc>

#include "Packet.hpp"

namespace sysc_netw {

namespace detail {

using namespace sc_core;

struct Consumer : sc_module {
    Consumer(const sc_module_name& name)
        : sc_module { name }
        , SC_NAMED(source) {
    }

    Packet::ptr recv() {
        auto packet_ptr = source->read();
        packet_ptr->time2 = sc_time_stamp();
        return packet_ptr;
    }

    sc_port<sc_fifo_in_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND> source;
};

} // namespace detail

using detail::Consumer;

} // namespace sysc_netw

#endif // SYSC_NETW_CONSUMER_INCLUDED
