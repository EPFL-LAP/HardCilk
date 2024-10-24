#include "paper_exp_dae_0__peCountExecute_1__vssNumberExecute_1.hpp"

namespace paper_exp_dae_0::generated
{

paper_exp_dae_0__peCountExecute_1__vssNumberExecute_1::paper_exp_dae_0__peCountExecute_1__vssNumberExecute_1(sc_core::sc_module_name const &name):
    Vpaper_exp_dae_0__peCountExecute_1__vssNumberExecute_1(name),
    execute_stealSide_taskOut_0_signals_("execute_stealSide_taskOut_0_signals"),
    execute_stealSide_taskIn_0_signals_("execute_stealSide_taskIn_0_signals"),
    execute_stealSide_axi_mgmt_vss_0_signals_("execute_stealSide_axi_mgmt_vss_0_signals"),
    execute_stealSide_vss_axi_full_0_signals_("execute_stealSide_vss_axi_full_0_signals"),
    execute_stealSide_taskOut_0_bridge_("execute_stealSide_taskOut_0_bridge"),
    execute_stealSide_taskIn_0_bridge_("execute_stealSide_taskIn_0_bridge"),
    execute_stealSide_axi_mgmt_vss_0_bridge_("execute_stealSide_axi_mgmt_vss_0_bridge"),
    execute_stealSide_vss_axi_full_0_bridge_("execute_stealSide_vss_axi_full_0_bridge")
    
{
    do_connect_();

    /* initialize the map for accessing TLM sockets */
    sockets_["execute_stealSide_taskOut_0"] = &execute_stealSide_taskOut_0_bridge_.socket;
    sockets_["execute_stealSide_taskIn_0"] = &execute_stealSide_taskIn_0_bridge_.tgt_socket;
    sockets_["execute_stealSide_axi_mgmt_vss_0"] = &execute_stealSide_axi_mgmt_vss_0_bridge_.tgt_socket;
    sockets_["execute_stealSide_vss_axi_full_0"] = &execute_stealSide_vss_axi_full_0_bridge_.socket;

    /* thread that generates the ACTIVE_LOW reset signal */
    SC_THREAD(reset_n_generate_);
    sensitive << reset;

    do_init_();
}


void paper_exp_dae_0__peCountExecute_1__vssNumberExecute_1::reset_n_generate_()
{
    reset_n_.write(!reset.read());
}

void paper_exp_dae_0__peCountExecute_1__vssNumberExecute_1::do_connect_()
{
    /* initialize bridges */
    execute_stealSide_taskOut_0_bridge_.clk(clock);
    execute_stealSide_taskOut_0_bridge_.resetn(reset_n_);
    
    execute_stealSide_taskIn_0_bridge_.clk(clock);
    execute_stealSide_taskIn_0_bridge_.resetn(reset_n_);
    
    execute_stealSide_axi_mgmt_vss_0_bridge_.clk(clock);
    execute_stealSide_axi_mgmt_vss_0_bridge_.resetn(reset_n_);
    
    execute_stealSide_vss_axi_full_0_bridge_.clk(clock);
    execute_stealSide_vss_axi_full_0_bridge_.resetn(reset_n_);
    


    /* connect bridges */
    execute_stealSide_taskOut_0_signals_.connect(execute_stealSide_taskOut_0_bridge_);
    execute_stealSide_taskIn_0_signals_.connect(execute_stealSide_taskIn_0_bridge_);
    execute_stealSide_axi_mgmt_vss_0_signals_.connect(execute_stealSide_axi_mgmt_vss_0_bridge_);
    execute_stealSide_vss_axi_full_0_signals_.connect(execute_stealSide_vss_axi_full_0_bridge_);


    /* connect signals */
    /* connecting AXI Stream interface with name: execute_stealSide_taskOut_0 */
    /* is_slave = False */
    execute_stealSide_taskOut_0_TREADY(execute_stealSide_taskOut_0_signals_.tready);
    execute_stealSide_taskOut_0_TVALID(execute_stealSide_taskOut_0_signals_.tvalid);
    execute_stealSide_taskOut_0_TDATA(execute_stealSide_taskOut_0_signals_.tdata);
    
    
    /* connecting AXI Stream interface with name: execute_stealSide_taskIn_0 */
    /* is_slave = True */
    execute_stealSide_taskIn_0_TREADY(execute_stealSide_taskIn_0_signals_.tready);
    execute_stealSide_taskIn_0_TVALID(execute_stealSide_taskIn_0_signals_.tvalid);
    execute_stealSide_taskIn_0_TDATA(execute_stealSide_taskIn_0_signals_.tdata);
    
    
    /* connecting AXI4-Lite interface with name: execute_stealSide_axi_mgmt_vss_0 */
    /* is_slave = True */
    execute_stealSide_axi_mgmt_vss_0_AWVALID(execute_stealSide_axi_mgmt_vss_0_signals_.awvalid);
    execute_stealSide_axi_mgmt_vss_0_AWREADY(execute_stealSide_axi_mgmt_vss_0_signals_.awready);
    execute_stealSide_axi_mgmt_vss_0_AWADDR(execute_stealSide_axi_mgmt_vss_0_signals_.awaddr);
    execute_stealSide_axi_mgmt_vss_0_AWPROT(execute_stealSide_axi_mgmt_vss_0_signals_.awprot);
    execute_stealSide_axi_mgmt_vss_0_WVALID(execute_stealSide_axi_mgmt_vss_0_signals_.wvalid);
    execute_stealSide_axi_mgmt_vss_0_WREADY(execute_stealSide_axi_mgmt_vss_0_signals_.wready);
    execute_stealSide_axi_mgmt_vss_0_WDATA(execute_stealSide_axi_mgmt_vss_0_signals_.wdata);
    execute_stealSide_axi_mgmt_vss_0_WSTRB(execute_stealSide_axi_mgmt_vss_0_signals_.wstrb);
    execute_stealSide_axi_mgmt_vss_0_BVALID(execute_stealSide_axi_mgmt_vss_0_signals_.bvalid);
    execute_stealSide_axi_mgmt_vss_0_BREADY(execute_stealSide_axi_mgmt_vss_0_signals_.bready);
    execute_stealSide_axi_mgmt_vss_0_BRESP(execute_stealSide_axi_mgmt_vss_0_signals_.bresp);
    execute_stealSide_axi_mgmt_vss_0_ARVALID(execute_stealSide_axi_mgmt_vss_0_signals_.arvalid);
    execute_stealSide_axi_mgmt_vss_0_ARREADY(execute_stealSide_axi_mgmt_vss_0_signals_.arready);
    execute_stealSide_axi_mgmt_vss_0_ARADDR(execute_stealSide_axi_mgmt_vss_0_signals_.araddr);
    execute_stealSide_axi_mgmt_vss_0_ARPROT(execute_stealSide_axi_mgmt_vss_0_signals_.arprot);
    execute_stealSide_axi_mgmt_vss_0_RVALID(execute_stealSide_axi_mgmt_vss_0_signals_.rvalid);
    execute_stealSide_axi_mgmt_vss_0_RREADY(execute_stealSide_axi_mgmt_vss_0_signals_.rready);
    execute_stealSide_axi_mgmt_vss_0_RDATA(execute_stealSide_axi_mgmt_vss_0_signals_.rdata);
    execute_stealSide_axi_mgmt_vss_0_RRESP(execute_stealSide_axi_mgmt_vss_0_signals_.rresp);
    
    
    /* connecting AXI4 interface with name: execute_stealSide_vss_axi_full_0 */
    /* is_slave = False */
    execute_stealSide_vss_axi_full_0_AWVALID(execute_stealSide_vss_axi_full_0_signals_.awvalid);
    execute_stealSide_vss_axi_full_0_AWREADY(execute_stealSide_vss_axi_full_0_signals_.awready);
    execute_stealSide_vss_axi_full_0_AWADDR(execute_stealSide_vss_axi_full_0_signals_.awaddr);
    execute_stealSide_vss_axi_full_0_AWPROT(execute_stealSide_vss_axi_full_0_signals_.awprot);
    execute_stealSide_vss_axi_full_0_AWREGION(execute_stealSide_vss_axi_full_0_signals_.awregion);
    execute_stealSide_vss_axi_full_0_AWQOS(execute_stealSide_vss_axi_full_0_signals_.awqos);
    execute_stealSide_vss_axi_full_0_AWCACHE(execute_stealSide_vss_axi_full_0_signals_.awcache);
    execute_stealSide_vss_axi_full_0_AWBURST(execute_stealSide_vss_axi_full_0_signals_.awburst);
    execute_stealSide_vss_axi_full_0_AWSIZE(execute_stealSide_vss_axi_full_0_signals_.awsize);
    execute_stealSide_vss_axi_full_0_AWLEN(execute_stealSide_vss_axi_full_0_signals_.awlen);
    execute_stealSide_vss_axi_full_0_AWID(execute_stealSide_vss_axi_full_0_signals_.awid);
    execute_stealSide_vss_axi_full_0_AWLOCK(execute_stealSide_vss_axi_full_0_signals_.awlock);
    execute_stealSide_vss_axi_full_0_WID(execute_stealSide_vss_axi_full_0_signals_.wid);
    execute_stealSide_vss_axi_full_0_WVALID(execute_stealSide_vss_axi_full_0_signals_.wvalid);
    execute_stealSide_vss_axi_full_0_WREADY(execute_stealSide_vss_axi_full_0_signals_.wready);
    execute_stealSide_vss_axi_full_0_WDATA(execute_stealSide_vss_axi_full_0_signals_.wdata);
    execute_stealSide_vss_axi_full_0_WSTRB(execute_stealSide_vss_axi_full_0_signals_.wstrb);
    execute_stealSide_vss_axi_full_0_WLAST(execute_stealSide_vss_axi_full_0_signals_.wlast);
    execute_stealSide_vss_axi_full_0_BVALID(execute_stealSide_vss_axi_full_0_signals_.bvalid);
    execute_stealSide_vss_axi_full_0_BREADY(execute_stealSide_vss_axi_full_0_signals_.bready);
    execute_stealSide_vss_axi_full_0_BRESP(execute_stealSide_vss_axi_full_0_signals_.bresp);
    execute_stealSide_vss_axi_full_0_BID(execute_stealSide_vss_axi_full_0_signals_.bid);
    execute_stealSide_vss_axi_full_0_ARVALID(execute_stealSide_vss_axi_full_0_signals_.arvalid);
    execute_stealSide_vss_axi_full_0_ARREADY(execute_stealSide_vss_axi_full_0_signals_.arready);
    execute_stealSide_vss_axi_full_0_ARADDR(execute_stealSide_vss_axi_full_0_signals_.araddr);
    execute_stealSide_vss_axi_full_0_ARPROT(execute_stealSide_vss_axi_full_0_signals_.arprot);
    execute_stealSide_vss_axi_full_0_ARREGION(execute_stealSide_vss_axi_full_0_signals_.arregion);
    execute_stealSide_vss_axi_full_0_ARQOS(execute_stealSide_vss_axi_full_0_signals_.arqos);
    execute_stealSide_vss_axi_full_0_ARCACHE(execute_stealSide_vss_axi_full_0_signals_.arcache);
    execute_stealSide_vss_axi_full_0_ARBURST(execute_stealSide_vss_axi_full_0_signals_.arburst);
    execute_stealSide_vss_axi_full_0_ARSIZE(execute_stealSide_vss_axi_full_0_signals_.arsize);
    execute_stealSide_vss_axi_full_0_ARLEN(execute_stealSide_vss_axi_full_0_signals_.arlen);
    execute_stealSide_vss_axi_full_0_ARID(execute_stealSide_vss_axi_full_0_signals_.arid);
    execute_stealSide_vss_axi_full_0_ARLOCK(execute_stealSide_vss_axi_full_0_signals_.arlock);
    execute_stealSide_vss_axi_full_0_RVALID(execute_stealSide_vss_axi_full_0_signals_.rvalid);
    execute_stealSide_vss_axi_full_0_RREADY(execute_stealSide_vss_axi_full_0_signals_.rready);
    execute_stealSide_vss_axi_full_0_RDATA(execute_stealSide_vss_axi_full_0_signals_.rdata);
    execute_stealSide_vss_axi_full_0_RRESP(execute_stealSide_vss_axi_full_0_signals_.rresp);
    execute_stealSide_vss_axi_full_0_RID(execute_stealSide_vss_axi_full_0_signals_.rid);
    execute_stealSide_vss_axi_full_0_RLAST(execute_stealSide_vss_axi_full_0_signals_.rlast);
    
    
}

void paper_exp_dae_0__peCountExecute_1__vssNumberExecute_1::do_init_()
{
    set("clock", clock);
    set("reset", reset);
    set("description", description);
}

hardcilk::desc::HardCilkSystem paper_exp_dae_0__peCountExecute_1__vssNumberExecute_1::description = [] {
    using namespace hardcilk::desc;
    auto description = HardCilkSystem{
        .tasks = []() {
            std::map<std::string, Task> r;
            r["execute"] = Task{
                .name = "execute",
                .parent = "/tasks",
                .path = "/tasks:execute",
                .isRoot = true,
                .isCont = false,
                .widthTask = 64,
                .widthCont = 64,
                .widthArg = 64,
                .numProcessingElements = 1,
                .processingElements = []() {
                    std::map<std::string, ProcessingElement> r;
                    r["pe_0"] = ProcessingElement{
                        .name = "pe_0",
                        .parent = "/tasks:execute",
                        .path = "/tasks:execute/processingElements:pe_0",
                        .portName = "execute_PE_0",
                        .interfaces = []() {
                            std::map<std::string, Interface> r;
                            r["mem"] = Interface{
                                .name = "mem",
                                .parent = "/tasks:execute/processingElements:pe_0",
                                .path = "/tasks:execute/processingElements:pe_0/interfaces:mem",
                                .portName = "execute_PE_0_mem",
                                .protocol = InterfaceProtocol::Axi4,
                                .config = Axi4Config{
                                    .addr_width = 64,
                                    .data_width = 64,
                                    .id_width = 4,
                                    .axlen_width = 8,
                                    .axlock_width = 1,
                                    .awuser_width = 0,
                                    .aruser_width = 0,
                                    .wuser_width = 0,
                                    .ruser_width = 0,
                                    .buser_width = 0
                                }
                            };
                            r["taskIn"] = Interface{
                                .name = "taskIn",
                                .parent = "/tasks:execute/processingElements:pe_0",
                                .path = "/tasks:execute/processingElements:pe_0/interfaces:taskIn",
                                .portName = "execute_PE_0_taskIn",
                                .protocol = InterfaceProtocol::Axis,
                                .isSlave = true,
                                .config = AxisConfig{
                                    .data_width = 64,
                                    .user_width = 0,
                                    .no_tstrb = true,
                                    .no_tlast = true
                                }
                            };
                            r["taskOut"] = Interface{
                                .name = "taskOut",
                                .parent = "/tasks:execute/processingElements:pe_0",
                                .path = "/tasks:execute/processingElements:pe_0/interfaces:taskOut",
                                .portName = "execute_PE_0_taskOut",
                                .protocol = InterfaceProtocol::Axis,
                                .isSlave = false,
                                .config = AxisConfig{
                                    .data_width = 64,
                                    .user_width = 0,
                                    .no_tstrb = true,
                                    .no_tlast = true
                                }
                            };
                            return r;
                        }()
                    };
                    return r;
                }(),
                .numVirtualStealServers = 1,
                .capacityVirtualStealQueue = 10000000,
                .virtualStealServers = []() {
                    std::map<std::string, VirtualStealServer> r;
                    r["vss_0"] = VirtualStealServer{
                        .name = "vss_0",
                        .parent = "execute",
                        .path = "/tasks:execute/virtualStealServers:vss_0",
                        .addressSpace = AddressSpace{
                            .base = addr_type(0x000000003ff00000ull),
                            .size = addr_type(0x0000000000010000ull)
                        },
                        .pathInterface = "/system/interfaces:execute_stealSide_axi_mgmt_vss_0",
                        .capacity = 10000000,
                        .numBytesTask = 8
                    };
                    return r;
                }(),
                .numVirtualContinuationServers = 0,
                .capacityVirtualContinuationQueue = 0,
                .numArgRouteServers = 0,
                .capacityArgRouteServers = 0
            };
            return r;
        }(),
        .system = System{
            .name = "system",
            .parent = "/",
            .path = "/system",
            .interfaces = []() {
                std::map<std::string, Interface> r;
                r["execute_stealSide_taskOut_0"] = Interface{
                    .name = "execute_stealSide_taskOut_0",
                    .parent = "/system",
                    .path = "/system/interfaces:execute_stealSide_taskOut_0",
                    .portName = "execute_stealSide_taskOut_0",
                    .protocol = InterfaceProtocol::Axis,
                    .isSlave = false,
                    .config = AxisConfig{
                        .data_width = 64,
                        .user_width = 0,
                        .no_tstrb = true,
                        .no_tlast = true
                    }
                };
                r["execute_stealSide_taskIn_0"] = Interface{
                    .name = "execute_stealSide_taskIn_0",
                    .parent = "/system",
                    .path = "/system/interfaces:execute_stealSide_taskIn_0",
                    .portName = "execute_stealSide_taskIn_0",
                    .protocol = InterfaceProtocol::Axis,
                    .isSlave = true,
                    .config = AxisConfig{
                        .data_width = 64,
                        .user_width = 0,
                        .no_tstrb = true,
                        .no_tlast = true
                    }
                };
                r["execute_stealSide_axi_mgmt_vss_0"] = Interface{
                    .name = "execute_stealSide_axi_mgmt_vss_0",
                    .parent = "/system",
                    .path = "/system/interfaces:execute_stealSide_axi_mgmt_vss_0",
                    .portName = "execute_stealSide_axi_mgmt_vss_0",
                    .isSlave = true,
                    .config = Axi4liteConfig{
                        .addr_width = 6,
                        .data_width = 64
                    },
                    .addressSpace = AddressSpace{
                        .base = addr_type(0x000000003ff00000ull),
                        .size = addr_type(0x0000000000010000ull)
                    }
                };
                r["execute_stealSide_vss_axi_full_0"] = Interface{
                    .name = "execute_stealSide_vss_axi_full_0",
                    .parent = "/system",
                    .path = "/system/interfaces:execute_stealSide_vss_axi_full_0",
                    .portName = "execute_stealSide_vss_axi_full_0",
                    .isSlave = false,
                    .config = Axi4Config{
                        .addr_width = 64,
                        .data_width = 64,
                        .id_width = 4,
                        .axlen_width = 8,
                        .axlock_width = 1,
                        .awuser_width = 0,
                        .aruser_width = 0,
                        .wuser_width = 0,
                        .ruser_width = 0,
                        .buser_width = 0
                    }
                };
                return r;
            }()
        },
        .connections = std::vector<std::pair<std::string, std::string>>{
            std::pair<std::string, std::string>{
                "/system/interfaces:execute_stealSide_taskOut_0",
                "/tasks:execute/processingElements:pe_0/interfaces:taskIn"
            },
            std::pair<std::string, std::string>{
                "/tasks:execute/processingElements:pe_0/interfaces:taskOut",
                "/system/interfaces:execute_stealSide_taskIn_0"
            }
        },
        .widthAddress = 64,
        .widthContinuationCounter = 32,
        .spawnList = []() {
            std::map<std::string, std::vector<std::string>> r;
            r["execute"] = std::vector<std::string>{
                "execute"
            };
            return r;
        }(),
        .managementBase = addr_type(0x000000003ff00000ull),
        .memory = AddressSpace{
            .base = addr_type(0x0000000040000000ull),
            .size = addr_type(0x00000000c0000000ull)
        },
        .interconnectMasters = 3,
        .interconnectSlaves = 2,
        .isElaborated = true
    };
    return description;
}();



}