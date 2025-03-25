#include <testBench.h>

#include <verilated_vcd_sc.h>
#include <stdint.h>
#include <iostream>

static constexpr const char *name = "paper_exp1";

// this really does not belong here, but whatever
sc_time _mFpga_switchPeriod = sc_time(8, SC_NS);
sc_time _mFpga_linkToSwitchDelay = sc_time(500, SC_NS);
sc_time _mFpga_linkToSwitchPeriod = sc_time(8, SC_NS);
sc_time _mFpga_linkToFpgaDelay = sc_time(500, SC_NS);
sc_time _mFpga_linkToFpgaPeriod = sc_time(8, SC_NS);

uint32_t _exp1_baseDepth = 2;
uint32_t _exp1_branchFactor = 6;
uint32_t _exp1_initCount = 6;
uint32_t _exp1_delay = 32;

#include <map>
#include <sstream>

std::map<std::string, std::string> parseArgs(int argc, char **argv)
{
    std::map<std::string, std::string> result;

    for (unsigned i = 1; i < argc; ++i)
    {
        auto arg = std::string(argv[i]);

        if (arg.size() > 2)
        {
            if (arg.substr(0, 2) == "-D")
            {
                std::istringstream iss(arg.substr(2));

                std::string name, value;
                std::getline(iss, name, '=');
                std::getline(iss, value);

                std::cout << "Found argument: '" << name << "' -> '" << value << "'\n";

                result[name] = value;
            }
        }
    }

    return result;
}

sc_time scTimeFromString(std::string const &str)
{
    // format: <double>_<unit>
    std::istringstream iss(str);

    std::string num_str, unit_str;
    std::getline(iss, num_str, '_');
    std::getline(iss, unit_str);

    auto num = std::stof(num_str);

    if (unit_str == "ns")
        return sc_time(num, SC_NS);

    else if (unit_str == "us")
        return sc_time(num, SC_US);

    throw std::runtime_error("not recognized unit!");
}

int sc_main(int argc, char **argv)
{
#ifdef VERILATED_TRACE_ENABLED
    Verilated::traceEverOn(true);
#endif
    Verilated::commandArgs(argc, argv);

    {
        auto args = parseArgs(argc, argv);

        if (auto it = args.find("mFpga_switchPeriod"); it != args.end())
        {
            _mFpga_switchPeriod = scTimeFromString(it->second);
        }

        if (auto it = args.find("mFpga_linkToSwitchDelay"); it != args.end())
        {
            _mFpga_linkToSwitchDelay = scTimeFromString(it->second);
        }

        if (auto it = args.find("mFpga_linkToSwitchPeriod"); it != args.end())
        {
            _mFpga_linkToSwitchPeriod = scTimeFromString(it->second);
        }

        if (auto it = args.find("mFpga_linkToFpgaDelay"); it != args.end())
        {
            _mFpga_linkToFpgaDelay = scTimeFromString(it->second);
        }

        if (auto it = args.find("mFpga_linkToFpgaPeriod"); it != args.end())
        {
            _mFpga_linkToFpgaPeriod = scTimeFromString(it->second);
        }

        if (auto it = args.find("exp1_baseDepth"); it != args.end())
        {
            _exp1_baseDepth = static_cast<uint32_t>(std::stoul(it->second));
        }

        if (auto it = args.find("exp1_branchFactor"); it != args.end())
        {
            _exp1_branchFactor = static_cast<uint32_t>(std::stoul(it->second));
        }

        if (auto it = args.find("exp1_initCount"); it != args.end())
        {
            _exp1_initCount = static_cast<uint32_t>(std::stoul(it->second));
        }

        if (auto it = args.find("exp1_delay"); it != args.end())
        {
            _exp1_delay = static_cast<uint32_t>(std::stoul(it->second));
        }
    }

    TestBench testBench("testBench");

    sc_start(SC_ZERO_TIME);

#ifdef VERILATED_TRACE_ENABLED
    auto tfp = std::make_unique<VerilatedVcdSc>();
    testBench.myModule->traceVerilated(tfp.get(), 99);
    tfp->open(fmt::format("{}.vcd", name).c_str());
#endif

    sc_start(1000, SC_MS);

#ifdef VERILATED_TRACE_ENABLED
    tfp->close();
#endif

    return 0;
}