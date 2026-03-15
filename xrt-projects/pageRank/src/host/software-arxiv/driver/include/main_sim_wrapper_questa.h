#ifndef _SCGENMOD_main_sim_
#define _SCGENMOD_main_sim_

#include "systemc.h"

#ifdef MTI_SYSTEMC
class main_sim : public sc_foreign_module
#else
class main_sim : public sc_module
#endif
{
public:
    sc_out<sc_logic> HBM_CATTRIP_LS;
    sc_in<sc_logic> PCIE_PERST_LS_65;
    sc_in<sc_logic> SYSCLK2_clk_n;
    sc_in<sc_logic> SYSCLK2_clk_p;
    sc_in<sc_logic> SYSCLK3_clk_n;
    sc_in<sc_logic> SYSCLK3_clk_p;
    sc_in<sc_logic> axi_vip_clk;
    sc_in<sc_logic> axi_vip_aresetn;

#ifdef MTI_SYSTEMC

  main_sim(sc_module_name nm, const char* hdl_name) : sc_foreign_module(nm),

#else

    main_sim(sc_module_name nm)
     : sc_module(nm),
     
#endif
       HBM_CATTRIP_LS("HBM_CATTRIP_LS"),
       PCIE_PERST_LS_65("PCIE_PERST_LS_65"),
       SYSCLK2_clk_n("SYSCLK2_clk_n"),
       SYSCLK2_clk_p("SYSCLK2_clk_p"),
       SYSCLK3_clk_n("SYSCLK3_clk_n"),
       SYSCLK3_clk_p("SYSCLK3_clk_p"),
       axi_vip_clk("axi_vip_clk"),
       axi_vip_aresetn("axi_vip_aresetn")
      {
        #ifdef MTI_SYSTEMC
          elaborate_foreign_module(hdl_name);
        #endif
      }
      
    ~main_sim()
    {}

};

#endif

