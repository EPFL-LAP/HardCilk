#ifndef PROTOCOLS_HPP_INCLUDED
#define PROTOCOLS_HPP_INCLUDED

#include <jqr/comp_eq.hpp>
#include <jqr/core.hpp>
#include <jqr/dump.hpp>

#include <fmt/core.h>

#include <systemc>

// clang-format off

struct axis4_mFPGA {
    // TODO: Make this configurable such that other people can use it for any axi4s interface

    sc_dt::sc_bv<512> data;
    sc_dt::sc_bv<64> strb;
    sc_dt::sc_bv<64> keep;
    bool last;
    sc_dt::sc_bv<4> dest;


    JQR_DECL(
        axis4_mFPGA,
        JQR_MEMBER(data),
        JQR_MEMBER(strb),
        JQR_MEMBER(keep),
        JQR_MEMBER(last),
        JQR_MEMBER(dest)
    )

    JQR_TO_STRING
    JQR_OSTREAM
    JQR_COMP_EQ

};

struct axis4_mFPGA_Signals {
    using value_type = axis4_mFPGA;

    axis4_mFPGA_Signals(const char* name)
        : data(fmt::format("{}_data", name).c_str())
        , strb(fmt::format("{}_strb", name).c_str())
        , keep(fmt::format("{}_keep", name).c_str())
        , last(fmt::format("{}_last", name).c_str())
        , dest(fmt::format("{}_dest", name).c_str()) {}

    sc_core::sc_signal<sc_dt::sc_bv<512>, sc_core::SC_MANY_WRITERS> data;
    sc_core::sc_signal<sc_dt::sc_bv<64>, sc_core::SC_MANY_WRITERS> strb;
    sc_core::sc_signal<sc_dt::sc_bv<64>, sc_core::SC_MANY_WRITERS> keep;
    sc_core::sc_signal<bool, sc_core::SC_MANY_WRITERS> last;
    sc_core::sc_signal<sc_dt::sc_bv<4>, sc_core::SC_MANY_WRITERS> dest;

    void readTo(value_type& x) const {
        x.~value_type();
        
        new (&x) value_type {
            data.read(),
            strb.read(),
            keep.read(),
            last.read(),
            dest.read()
        };
    }

    void writeFrom(const value_type& x) {
        data.write(x.data);
        strb.write(x.strb);
        keep.write(x.keep);
        last.write(x.last);
        dest.write(x.dest);
    }
};

// clang-format on

#endif // PROTOCOLS_HPP_INCLUDED
