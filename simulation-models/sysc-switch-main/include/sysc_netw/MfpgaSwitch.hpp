#ifndef __MFPGA_SWITCH_HPP__
#define __MFPGA_SWITCH_HPP__

#include <fmt/core.h>

#include <algorithm>
#include <memory>
#include <random>
#include <vector>

#include <cassert>

#include <systemc>
#include <chext_test/elastic/Driver.hpp>

#include <sysc_netw/sysc_netw.hpp>
#include <Protocols.hpp>

namespace sysc_netw {
namespace detail {

using namespace sc_core;
using namespace sc_dt;

struct MfpgaSwitchConfig {
    unsigned numNodes { 8 };

    sc_time switchPeriod { sc_time(500, SC_NS) };
    sc_time linkToSwitchDelay { sc_time(8, SC_NS) };
    sc_time linkToSwitchPeriod { sc_time(500, SC_NS) };

    sc_time linkToFpgaDelay { sc_time(8, SC_NS) };
    sc_time linkToFpgaPeriod { sc_time(500, SC_NS) };
};

struct MfpgaSwitch : sc_core::sc_module {
    MfpgaSwitch(
        sc_module_name const& name,
        MfpgaSwitchConfig const& config,
        std::vector<chext_test::elastic::Sink<axis4_mFPGA_Signals>*> fpgaSinks,
        std::vector<chext_test::elastic::Source<axis4_mFPGA_Signals>*> fpgaSources
    )
        : sc_module { name }
        , config_ { config }
        , fpgaSinks_ { std::move(fpgaSinks) }
        , fpgaSources_ { std::move(fpgaSources) } {

        assert(fpgaSinks_.size() == config_.numNodes);
        assert(fpgaSources_.size() == config_.numNodes);

        switch_ = std::make_unique<sysc_netw::Crossbar>("switch", config_.numNodes, config_.switchPeriod);

        for (unsigned i = 0; i < config_.numNodes; ++i) {
            auto generator = std::make_unique<sysc_netw::Generator>(fmt::format("generator_{}", i).c_str());

            auto toSwitchLink = std::make_unique<sysc_netw::Link>(
                fmt::format("toSwitchLink_{}", i).c_str(),
                config_.linkToSwitchPeriod,
                config_.linkToSwitchDelay
            );

            // HARDCODED: 16, buffer size
            connect_(generator->sink, toSwitchLink->source, 16);

            // HARDCODED: 16, buffer size
            connect_(toSwitchLink->sink, switch_->source_at(i), 16);

            auto consumer = std::make_unique<sysc_netw::Consumer>(fmt::format("consumer_{}", i).c_str());

            // HARDCODED: sc_time
            auto fromSwitchLink = std::make_unique<sysc_netw::Link>(
                fmt::format("fromSwitchLink_{}", i).c_str(),
                config_.linkToFpgaPeriod,
                config_.linkToFpgaDelay
            );

            // HARDCODED: 16, buffer size
            connect_(switch_->sink_at(i), fromSwitchLink->source, 16);

            // HARDCODED: 16, buffer size
            connect_(fromSwitchLink->sink, consumer->source, 16);

            generators_.push_back(std::move(generator));
            toSwitchLinks_.push_back(std::move(toSwitchLink));

            consumers_.push_back(std::move(consumer));
            fromSwitchLinks_.push_back(std::move(fromSwitchLink));

            sc_spawn([this, i] { generatorProcess(i); });
            sc_spawn([this, i] { consumerProcess(i); });
        }
    }

    auto const& config() const noexcept {
        return config_;
    }

private:
    MfpgaSwitchConfig config_;

    std::vector<chext_test::elastic::Sink<axis4_mFPGA_Signals>*> fpgaSinks_;
    std::vector<chext_test::elastic::Source<axis4_mFPGA_Signals>*> fpgaSources_;

    // instantiate network stuff here...
    std::vector<std::unique_ptr<sysc_netw::Generator>> generators_;
    std::vector<std::unique_ptr<sysc_netw::Consumer>> consumers_;

    std::vector<std::unique_ptr<sysc_netw::Link>> toSwitchLinks_;
    std::vector<std::unique_ptr<sysc_netw::Link>> fromSwitchLinks_;

    std::unique_ptr<sysc_netw::Crossbar> switch_;

    sysc_netw::Connect connect_;

    void generatorProcess(unsigned idx) {
        auto sink = fpgaSinks_[idx];
        auto generator = generators_[idx].get();

        // wait(sc_time(1, SC_US));

        while (true) {
            auto inPacket = new axis4_mFPGA(sink->receive());
            auto asciiData = inPacket->data.range(31, 0).to_string(sc_dt::SC_HEX);
            // fmt::println("received '{}' from node[{}] at {}.", asciiData, idx, sc_time_stamp().to_string());

            assert(inPacket->dest.to_uint() < config_.numNodes);

            auto outPacket = sysc_netw::Packet::create();
            outPacket->payloadPtr = inPacket;
            outPacket->asciiData = asciiData;
            outPacket->nextRoute.emplace_back(Hop { .localDestIndex = inPacket->dest.to_uint() });

            generator->send(outPacket);
        }
    }

    void consumerProcess(unsigned idx) {
        auto source = fpgaSources_[idx];
        auto consumer = consumers_[idx].get();

        // wait(sc_time(1, SC_US));

        while (true) {
            auto inPacket = consumer->recv();
            // fmt::println("received '{}' from network[{}] at {}.", inPacket->asciiData, idx, sc_time_stamp().to_string());

            axis4_mFPGA* outPacket = (axis4_mFPGA*)inPacket->payloadPtr;
            source->send(*outPacket);
            delete outPacket;
        }
    }
};

} // namespace detail

using detail::MfpgaSwitchConfig;
using detail::MfpgaSwitch;

} // namespace sysc_netw

#endif // __MFPGA_SWITCH_HPP__
