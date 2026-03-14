#ifndef SYSC_NETW_ARBITER_INCLUDED
#define SYSC_NETW_ARBITER_INCLUDED

#include <memory>
#include <random>

#include <systemc>

#include "Packet.hpp"

namespace sysc_netw {

namespace detail {

using namespace sc_core;

struct ArbiterFunction {
    ArbiterFunction(unsigned n)
        : valids_(n, false) {
    }

    bool valid(unsigned idx) const noexcept {
        return valids_[idx];
    }

    void valid(unsigned idx, bool v) noexcept {
        valids_[idx] = v;
    }

    std::vector<int> const& valids() const noexcept {
        return valids_;
    }

    unsigned count() const noexcept {
        return valids_.size();
    }

    virtual int choose() = 0;

    virtual ~ArbiterFunction() = default;

private:
    std::vector<int> valids_;
};

struct PriorityArbiter : ArbiterFunction {
    using ArbiterFunction::ArbiterFunction;

    int choose() override {
        unsigned n = count();

        for (unsigned idx = 0; idx < n; ++idx) {
            if (valid(idx))
                return idx;
        }

        return -1;
    }
};

struct RoundRobinArbiter : ArbiterFunction {
    using ArbiterFunction::ArbiterFunction;

    int choose() override {
        unsigned offset = lastIdx_ + 1;
        unsigned n = count();

        for (unsigned idx = 0; idx < n; ++idx) {
            unsigned idx0 = (idx + offset) % n;

            if (valid(idx0)) {
                lastIdx_ = idx0;
                return idx0;
            }
        }

        return -1;
    }

private:
    int lastIdx_ = -1;
};

struct RandomArbiter : ArbiterFunction {
    RandomArbiter(unsigned n, std::uint64_t seed = 0x783264)
        : ArbiterFunction(n)
        , gen_(seed)
        , dist_(0, n - 1) {
    }

    int choose() override {
        // check if there is a valid signal
        // do not disrupt the state of the random generator
        // unless the requested value is used

        bool hasValid = false;

        for (auto valid : valids()) {
            if (valid) {
                hasValid = true;
                break;
            }
        }

        if (!hasValid) {
            return -1;
        }

        unsigned offset = dist_(gen_);
        unsigned n = count();

        for (unsigned idx = 0; idx < n; ++idx) {
            unsigned idx0 = (idx + offset) % n;

            if (valid(idx0))
                return idx0;
        }

        // TODO: mark unreachable
        return -1;
    }

private:
    std::mt19937_64 gen_;
    std::uniform_int_distribution<unsigned> dist_;
};

struct Arbiter : sc_module {
    Arbiter(const sc_module_name& name, unsigned n, sc_time const& period)
        : sc_module { name }
        , SC_NAMED(sink)
        , SC_NAMED(clock_, period)
        , sources_(n) {
        SC_THREAD(run);
    }

    sc_port_b<sc_fifo_in_if<Packet::ptr>>& source_at(unsigned idx) {
        return sources_.at(idx);
    }

    sc_port<sc_fifo_out_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND> sink;

private:
    sc_clock clock_;
    std::vector<sc_port<sc_fifo_in_if<Packet::ptr>, 1, SC_ZERO_OR_MORE_BOUND>> sources_;

    void run() {
        std::random_device rd;
        std::mt19937 g(rd());

        std::vector<unsigned> v(sources_.size());

        for (unsigned i = 0; i < sources_.size(); ++i) {
            v[i] = i;
        }

        while (true) {
            std::shuffle(v.begin(), v.end(), g);

            auto it = std::find_if(
                v.begin(),
                v.end(),
                [this](auto x) {
                    return sources_[x]->num_available() > 0;
                }
            );

            if (it != v.end() && sink->num_free() > 0) {
                sink->write(sources_[*it]->read());
            }

            wait(clock_.posedge_event());
        }
    }
};

} // namespace detail

using detail::Arbiter;

using detail::PriorityArbiter;
using detail::RandomArbiter;
using detail::RoundRobinArbiter;

using detail::Arbiter;

} // namespace sysc_netw

#endif // SYSC_NETW_ARBITER_INCLUDED
