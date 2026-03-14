#include <fmt/core.h>

#include <algorithm>
#include <memory>
#include <random>
#include <vector>

#include <cassert>

#include <systemc>

#include <NetworkNode.hpp>
#include <sysc_netw/sysc_netw.hpp>

using namespace sysc_netw;
using namespace sc_core;

#include <chext_test/elastic/Driver.hpp>
#include <sysc_netw/Protocols.hpp>

struct MfpgaSwitch : sc_core::sc_module {
    MfpgaSwitch(
        sc_module_name const& name,
        std::vector<chext_test::elastic::Sink<axis4_mFPGA_Signals>*> fpgaSinks,
        std::vector<chext_test::elastic::Source<axis4_mFPGA_Signals>*> fpgaSources
    )
        : sc_module { name }
        , fpgaSinks_ { std::move(fpgaSinks) }
        , fpgaSources_ { std::move(fpgaSources) } {

        assert(fpgaSinks_.size() == fpgaSources_.size());
        assert(fpgaSinks_.size() > 0);

        unsigned n = 8;

        // HARDCODED: 8 and sc_time(500, SC_NS)
        switch_ = std::make_unique<sysc_netw::Crossbar>("switch", 8, sc_core::sc_time(500, SC_NS));

        // HARDCODED: 8
        for (unsigned i = 0; i < 8; ++i) {
            auto generator = std::make_unique<sysc_netw::Generator>(fmt::format("generator_{}", i).c_str());
            auto toSwitchLink = std::make_unique<sysc_netw::Link>(
                fmt::format("toSwitchLink_{}", i).c_str(),
                sc_core::sc_time(8, SC_NS),
                sc_core::sc_time(500, SC_NS)
            );

            // HARDCODED: 16
            connect_(generator->sink, toSwitchLink->source, 16);

            // HARDCODED: 16
            connect_(toSwitchLink->sink, switch_->source_at(i), 16);

            auto consumer = std::make_unique<sysc_netw::Consumer>(fmt::format("consumer_{}", i).c_str());

            auto fromSwitchLink = std::make_unique<sysc_netw::Link>(
                fmt::format("fromSwitchLink_{}", i).c_str(),
                sc_core::sc_time(8, SC_NS),
                sc_core::sc_time(500, SC_NS)
            );

            // HARDCODED: 16
            connect_(switch_->sink_at(i), fromSwitchLink->source, 16);

            // HARDCODED: 16
            connect_(fromSwitchLink->sink, consumer->source, 16);

            generators_.push_back(std::move(generator));
            toSwitchLinks_.push_back(std::move(toSwitchLink));

            consumers_.push_back(std::move(consumer));
            fromSwitchLinks_.push_back(std::move(fromSwitchLink));

            sc_spawn([this, i] { generatorProcess(i); });
            sc_spawn([this, i] { consumerProcess(i); });
        }
    }

private:
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

        wait(sc_time(1, SC_US));

        while (true) {
            auto inPacket = new axis4_mFPGA(sink->receive());
            auto asciiData = inPacket->data.range(31, 0).to_string(sc_dt::SC_HEX);
            fmt::println("received '{}' from node[{}] at {}.", asciiData, idx, sc_time_stamp().to_string());

            // HARDCODED
            assert(inPacket->dest.to_uint() < 8);

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

        wait(sc_time(1, SC_US));

        while (true) {
            auto inPacket = consumer->recv();
            fmt::println("received '{}' from network[{}] at {}.", inPacket->asciiData, idx, sc_time_stamp().to_string());

            axis4_mFPGA* outPacket = (axis4_mFPGA*)inPacket->payloadPtr;
            source->send(*outPacket);
            delete outPacket;
        }
    }
};

struct Top : sc_module {
    Top(sc_module_name const& name = "")
        : sc_module { name }
        , SC_NAMED(clk_, sc_time(2, SC_NS))
        , SC_NAMED(reset_) {

        std::vector<chext_test::elastic::Sink<axis4_mFPGA_Signals>*> fpgaSinks;
        std::vector<chext_test::elastic::Source<axis4_mFPGA_Signals>*> fpgaSources;

        // HARDCODED: 8
        for (unsigned i = 0; i < 8; ++i) {
            auto node = std::make_unique<NetworkNode>(fmt::format("node_{}", i).c_str());
            auto nodeId = std::make_unique<sc_signal<sc_dt::sc_bv<4>>>(fmt::format("nodeId_{}", i).c_str());

            fpgaSinks.push_back(&node->m_axis);
            fpgaSources.push_back(&node->s_axis);

            node->clock(clk_);
            node->reset(reset_);
            node->nodeId(*nodeId);

            nodeId->write(i);

            nodes_.push_back(std::move(node));
            nodeIds_.push_back(std::move(nodeId));
        }

        mfgpaSwitch_ = std::make_unique<MfpgaSwitch>("switch", std::move(fpgaSinks), std::move(fpgaSources));

        reset_.write(false);

        sc_spawn([this] {
            wait(sc_time(4, SC_NS));

            reset_.write(true);
            wait(sc_time(10, SC_NS));
            reset_.write(false);
        });
    }

private:
    sc_clock clk_;
    sc_signal<bool> reset_;
    std::vector<std::unique_ptr<sc_signal<sc_dt::sc_bv<4>>>> nodeIds_;

    std::unique_ptr<MfpgaSwitch> mfgpaSwitch_;
    std::vector<std::unique_ptr<NetworkNode>> nodes_;
};

int sc_main(int argc, char** argv) {
    sc_set_time_resolution(0.001, SC_NS);
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    Top top;
    sc_start(100000, SC_NS);
    // print_hierarchy(&top);
    return 0;
}
