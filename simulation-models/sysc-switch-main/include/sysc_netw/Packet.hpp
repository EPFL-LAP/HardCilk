#ifndef SYSC_NETW_PACKET_INCLUDED
#define SYSC_NETW_PACKET_INCLUDED

#include <cstdint>
#include <deque>
#include <iostream>
#include <sstream>

#include <fmt/format.h>
#include <fmt/ranges.h>

#include <systemc>

#include "util/reference_counted.hpp"

namespace sysc_netw::detail {

struct Hop;
struct Packet;

} // namespace sysc_netw::detail

namespace sysc_netw {

using Hop = detail::Hop;
using Packet = detail::Packet;

} // namespace sysc_netw

#include <fmt/ostream.h>

template<> struct fmt::formatter<sysc_netw::Hop> : fmt::ostream_formatter {};
template<> struct fmt::formatter<sysc_netw::Packet> : fmt::ostream_formatter {};

namespace sysc_netw::detail {

using namespace sc_core;

struct Hop {
    sc_module* component { nullptr };

    sc_time time1 { SC_ZERO_TIME };
    sc_time time2 { SC_ZERO_TIME };

    unsigned localDestIndex { 0 };

    std::string toString() const {
        std::string fields[] = {
            fmt::format("component = '{}@{}'", component ? component->name() : "<null>", static_cast<void*>(component)),
            fmt::format("time1 = '{}'", time1.to_string()),
            fmt::format("time2 = '{}'", time2.to_string()),
            fmt::format("localDestIndex = {}", localDestIndex)
        };

        return fmt::format("Hop({})", fmt::join(fields, ", "));
    }
};

std::ostream& operator<<(std::ostream& os, Hop const& x) {
    return (os << x.toString());
}

struct Packet : util::reference_counted_base<Packet> {
    using ptr = util::ptr<Packet>;

    static ptr create() {
        return { new Packet };
    }

    std::uint64_t id { 0 };
    sc_time time1 { SC_ZERO_TIME };
    sc_time time2 { SC_ZERO_TIME };
    bool done { false };

    std::string asciiData;
    std::vector<std::uint8_t> rawData;

    unsigned payloadType { 0 };
    void* payloadPtr { nullptr };
    std::size_t payloadSize { 0 };

    sc_module* src { nullptr };
    sc_module* dest { nullptr };

    std::deque<Hop> nextRoute;
    std::vector<Hop> prevRoute;

    std::string toString() const {
        std::string fields[] = {
            fmt::format("ptr = {}", (void*)this),
            fmt::format("id = {}", id),
            fmt::format("time1 = '{}'", time1.to_string()),
            fmt::format("time2 = '{}'", time2.to_string()),
            fmt::format("done = '{}'", done),
            fmt::format("src = '{}@{}'", src ? src->name() : "<null>", static_cast<void*>(src)),
            fmt::format("dest = '{}@{}'", dest ? dest->name() : "<null>", static_cast<void*>(dest)),
            fmt::format("asciiData = '{}'", asciiData),
            fmt::format("payloadType = {}", payloadType),
            fmt::format("payloadPtr = {}", payloadPtr),
            fmt::format("payloadSize = {}", payloadSize),
            fmt::format("prevRoute = [{}]", fmt::join(prevRoute, ", ")),
            fmt::format("nextRoute = [{}]", fmt::join(nextRoute, ", ")),
        };

        return fmt::format("Packet({})", fmt::join(fields, ", "));
    }
};

std::ostream& operator<<(std::ostream& os, Packet const& x) {
    return (os << x.toString());
}

std::ostream& operator<<(std::ostream& os, Packet::ptr const& x) {
    return (os << x->toString());
}

} // namespace sysc_netw::detail

#endif // SYSC_NETW_PACKET_INCLUDED
