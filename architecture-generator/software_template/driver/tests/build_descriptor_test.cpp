#include <hardcilk_build_descriptor.h>

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>

static HardCilkBuildDescriptor load(const std::string &path,
                                    const std::string &json)
{
  std::ofstream out(path.c_str());
  out << json;
  out.close();
  setenv("HARDCILK_HBM_DESCRIPTOR", path.c_str(), 1);
  return HardCilkBuildDescriptor::loadFromEnvironment();
}

int main()
{
  const std::string path = "/tmp/hardcilk_build_descriptor_test.json";

  const auto cached = load(path, R"({
    "schemaVersion":2,
    "build":{"architecture":"updated","argumentServer":"cached",
      "ramaMode":"descriptor-only",
      "globalStart":{"available":true,"enabled":true,"registerAddress":464}},
    "management":{"servers":[
      {"kind":"scheduler","task":"root","index":0,"baseAddress":16,
       "registerLayout":"scheduler-v1","queueCapacity":1024,
       "entryBytes":32,"hbmPort":7,"rootSeed":true},
      {"kind":"allocator","task":"cont","index":0,"baseAddress":80,
       "registerLayout":"allocator-v1","queueCapacity":2048,
       "entryBytes":8,"hbmPort":15,"rootSeed":false}
    ]}
  })");
  assert(cached.loaded);
  assert(cached.architecture == "updated");
  assert(cached.argumentServer == "cached");
  assert(cached.globalStartAvailable);
  assert(cached.globalStartEnabled);
  assert(cached.globalStartAddress == 464);
  assert(cached.serversFor("root", "scheduler").front().rootSeed);
  const auto cachedOverrides = cached.managementBankOverrides();
  assert(cachedOverrides.size() == 2);
  assert(cachedOverrides.front().region == "sched:root:0");
  assert(cachedOverrides.front().hbmPort == 7);

  const auto noCache = load(path, R"({
    "schemaVersion":2,
    "build":{"architecture":"updated","argumentServer":"no-cache",
      "ramaMode":"non-striped",
      "globalStart":{"available":true,"enabled":false,"registerAddress":null}},
    "management":{"servers":[
      {"kind":"scheduler","task":"root","index":0,"baseAddress":16,
       "registerLayout":"scheduler-v1","queueCapacity":512,
       "entryBytes":32,"hbmPort":9,"rootSeed":true}
    ]}
  })");
  assert(noCache.loaded);
  assert(noCache.argumentServer == "no-cache");
  assert(noCache.globalStartAvailable);
  assert(!noCache.globalStartEnabled);
  assert(noCache.serversFor("root", "scheduler").front().queueCapacity == 512);
  assert(noCache.managementBankOverrides().front().hbmPort == 9);

  const auto legacy = load(path, R"({
      "schemaVersion":2,
      "build":{"architecture":"legacy","argumentServer":"no-cache",
        "ramaMode":"striped",
        "globalStart":{"available":false,"enabled":false,"registerAddress":null}},
      "management":{"servers":[
        {"kind":"scheduler","task":"root","index":0,"baseAddress":16,
         "registerLayout":"scheduler-v1","queueCapacity":1024,
         "entryBytes":32,"hbmPort":7,"rootSeed":true},
        {"kind":"spawner","task":"child","index":0,"baseAddress":80,
         "registerLayout":"spawner-v1","queueCapacity":256,
         "entryBytes":32,"hbmPort":8,"rootSeed":false}
      ]}
    })");
  assert(legacy.loaded);
  assert(legacy.schemaVersion == 2);
  assert(legacy.architecture == "legacy");
  assert(legacy.argumentServer == "no-cache");
  assert(legacy.ramaMode == "striped");
  assert(!legacy.globalStartAvailable);
  assert(!legacy.globalStartEnabled);
  assert(legacy.serversFor("root", "scheduler").size() == 1);
  const auto spawners = legacy.serversFor("child", "spawner");
  assert(spawners.size() == 1);
  assert(spawners.front().baseAddress == 80);
  assert(spawners.front().hbmPort == 8);
  assert(legacy.managementBankOverrides().empty());

  const auto oldSidecar = load(path, R"({"schemaVersion":1,"ports":[]})");
  assert(!oldSidecar.loaded);
  assert(oldSidecar.servers.empty());

  std::remove(path.c_str());
  return 0;
}
