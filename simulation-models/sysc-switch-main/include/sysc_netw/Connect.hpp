#ifndef SYSC_NETW_CONNECT_INCLUDED
#define SYSC_NETW_CONNECT_INCLUDED

#include <memory>

#include <systemc>

namespace sysc_netw {

namespace detail {

using namespace sc_core;

struct Connect {
    template<typename T>
    void operator()(sc_port_b<sc_fifo_out_if<T>>& source, sc_port_b<sc_fifo_in_if<T>>& sink, unsigned capacity = 4) {
        connect_with_fifo<T>(source, sink, capacity);
    }

    template<typename T>
    void operator()(sc_port_b<sc_fifo_in_if<T>>& source, sc_port_b<sc_fifo_in_if<T>>& sink) {
        source(sink);
    }

    template<typename T>
    void operator()(sc_port_b<sc_fifo_out_if<T>>& source, sc_port_b<sc_fifo_out_if<T>>& sink) {
        source(sink);
    }

private:
    std::vector<std::unique_ptr<sc_object>> fifos_;

    template<typename T, typename Source, typename Sink>
    void connect_with_fifo(Source& source, Sink& sink, unsigned capacity) {
        auto name = fmt::format(
            "_CONNECT_{}_{}_TO_{}_{}",
            source.get_parent_object()->basename(), source.basename(),
            sink.get_parent_object()->basename(), sink.basename()
        );
        auto fifo = std::make_unique<sc_fifo<T>>(name.c_str(), capacity);

        source(*fifo);
        sink(*fifo);

        fifos_.emplace_back(std::move(fifo));
    }
};

} // namespace detail

using detail::Connect;

} // namespace sysc_netw

#endif // SYSC_NETW_CONNECT_INCLUDED
