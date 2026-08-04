#include <rama_striping.h>

#include <cassert>
#include <cstdio>
#include <cstdint>
#include <fstream>
#include <string>

int main()
{
  RamaHostMapping mapping;
  mapping.per_memory = true;
  mapping.runtime_enabled = true;
  mapping.first_bank = 0;
  mapping.memory_count = 16;
  mapping.fragment_bytes = 64;
  mapping.bytes_per_bank = 512ULL * 1024 * 1024;

  assert(mapping.physicalAddress(0) == 0);
  assert(mapping.physicalAddress(63) == 63);
  assert(mapping.physicalAddress(64) == mapping.bytes_per_bank);
  assert(mapping.physicalAddress(15 * 64) == 15 * mapping.bytes_per_bank);
  assert(mapping.physicalAddress(16 * 64) == 64);
  assert(mapping.physicalAddress(mapping.windowBytes() - 1) ==
         mapping.windowBytes() - 1);

  const std::string path = "/tmp/hardcilk_rama_striping_test.hbmports.json";
  {
    std::ofstream out(path.c_str());
    out << "{\"rama\":{\"ports\":[0],\"hostMapping\":"
           "{\"mode\":\"per_memory\",\"runtimeModes\":[\"hw\",\"questa\"],"
           "\"firstBank\":0,\"memoryCount\":16,\"fragmentBytes\":64,"
           "\"bytesPerBank\":536870912}}}";
  }
  setenv("HARDCILK_HBM_DESCRIPTOR", path.c_str(), 1);
  const RamaHostMapping hw = loadRamaHostMapping(std::string(), "hw");
  const RamaHostMapping hw_emu = loadRamaHostMapping(std::string(), "hw_emu");
  const RamaHostMapping questa = loadRamaHostMapping(std::string(), "questa");
  assert(hw.enabled());
  assert(!hw_emu.enabled());
  assert(questa.enabled());
  std::remove(path.c_str());
  return 0;
}
