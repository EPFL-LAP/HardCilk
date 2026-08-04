#pragma once

#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

// Host-side description of RAMA's G_MEM_INTERLEAVE_TYPE=per_memory address
// transform.  The generator writes this into <design>.hbmports.json; both the
// XRT and Questa backends consume that same sidecar rather than duplicating the
// architecture's striping constants in benchmark code.
struct RamaHostMapping
{
  bool per_memory = false;
  bool runtime_enabled = false;
  uint64_t first_bank = 0;
  uint64_t memory_count = 0;
  uint64_t fragment_bytes = 0;
  uint64_t bytes_per_bank = 0;
  std::string descriptor_path;

  bool enabled() const
  {
    return per_memory && runtime_enabled;
  }

  uint64_t windowBase() const
  {
    return first_bank * bytes_per_bank;
  }

  uint64_t windowBytes() const
  {
    return memory_count * bytes_per_bank;
  }

  uint64_t windowEnd() const
  {
    return windowBase() + windowBytes();
  }

  bool contains(uint64_t address) const
  {
    return enabled() && address >= windowBase() && address < windowEnd();
  }

  uint64_t stripeIndex(uint64_t logical_address) const
  {
    validateAddress(logical_address);
    const uint64_t relative = logical_address - windowBase();
    return (relative / fragment_bytes) % memory_count;
  }

  uint64_t bytesUntilFragmentEnd(uint64_t logical_address) const
  {
    validateAddress(logical_address);
    const uint64_t relative = logical_address - windowBase();
    return fragment_bytes - (relative % fragment_bytes);
  }

  uint64_t physicalAddress(uint64_t logical_address) const
  {
    validateAddress(logical_address);
    const uint64_t relative = logical_address - windowBase();
    const uint64_t fragment = relative / fragment_bytes;
    const uint64_t in_fragment = relative % fragment_bytes;
    const uint64_t bank = fragment % memory_count;
    const uint64_t bank_fragment = fragment / memory_count;
    return (first_bank + bank) * bytes_per_bank +
           bank_fragment * fragment_bytes + in_fragment;
  }

private:
  void validateAddress(uint64_t logical_address) const
  {
    if (!contains(logical_address))
      throw std::runtime_error("RAMA mapping requested outside its logical window");
  }
};

namespace hardcilk_rama_detail {

inline bool endsWith(const std::string &value, const std::string &suffix)
{
  return value.size() >= suffix.size() &&
         value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

inline std::string dirname(const std::string &path)
{
  const std::size_t slash = path.find_last_of('/');
  return slash == std::string::npos ? std::string(".") : path.substr(0, slash);
}

inline std::string basenameWithoutXclbin(const std::string &path)
{
  const std::size_t slash = path.find_last_of('/');
  std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
  if (endsWith(name, ".xclbin"))
    name.resize(name.size() - std::string(".xclbin").size());
  return name;
}

inline std::string readFile(const std::string &path)
{
  std::ifstream input(path.c_str(), std::ios::binary);
  if (!input)
    return std::string();
  std::ostringstream contents;
  contents << input.rdbuf();
  return contents.str();
}

inline std::string jsonObject(const std::string &json, const std::string &key)
{
  const std::string needle = std::string("\"") + key + "\"";
  const std::size_t key_pos = json.find(needle);
  if (key_pos == std::string::npos)
    return std::string();
  const std::size_t begin = json.find('{', key_pos + needle.size());
  if (begin == std::string::npos)
    return std::string();

  bool in_string = false;
  bool escaped = false;
  unsigned depth = 0;
  for (std::size_t i = begin; i < json.size(); ++i)
  {
    const char c = json[i];
    if (in_string)
    {
      if (escaped)
        escaped = false;
      else if (c == '\\')
        escaped = true;
      else if (c == '"')
        in_string = false;
      continue;
    }
    if (c == '"')
      in_string = true;
    else if (c == '{')
      ++depth;
    else if (c == '}' && --depth == 0)
      return json.substr(begin, i - begin + 1);
  }
  return std::string();
}

inline bool jsonString(const std::string &object, const std::string &key,
                       std::string &value)
{
  const std::string needle = std::string("\"") + key + "\"";
  const std::size_t key_pos = object.find(needle);
  if (key_pos == std::string::npos)
    return false;
  const std::size_t colon = object.find(':', key_pos + needle.size());
  const std::size_t quote = object.find('"', colon);
  if (colon == std::string::npos || quote == std::string::npos)
    return false;
  const std::size_t end = object.find('"', quote + 1);
  if (end == std::string::npos)
    return false;
  value = object.substr(quote + 1, end - quote - 1);
  return true;
}

inline bool jsonU64(const std::string &object, const std::string &key,
                    uint64_t &value)
{
  const std::string needle = std::string("\"") + key + "\"";
  const std::size_t key_pos = object.find(needle);
  if (key_pos == std::string::npos)
    return false;
  const std::size_t colon = object.find(':', key_pos + needle.size());
  if (colon == std::string::npos)
    return false;
  std::size_t begin = colon + 1;
  while (begin < object.size() &&
         (object[begin] == ' ' || object[begin] == '\t' ||
          object[begin] == '\n' || object[begin] == '\r'))
    ++begin;
  std::size_t end = begin;
  while (end < object.size() && object[end] >= '0' && object[end] <= '9')
    ++end;
  if (end == begin)
    return false;
  value = static_cast<uint64_t>(std::strtoull(object.substr(begin, end - begin).c_str(),
                                              nullptr, 10));
  return true;
}

inline bool runtimeListed(const std::string &object, const std::string &runtime)
{
  const std::size_t key = object.find("\"runtimeModes\"");
  if (key == std::string::npos)
    return false;
  const std::size_t begin = object.find('[', key);
  const std::size_t end = object.find(']', begin);
  if (begin == std::string::npos || end == std::string::npos)
    return false;
  return object.substr(begin, end - begin + 1)
             .find(std::string("\"") + runtime + "\"") != std::string::npos;
}

inline std::vector<std::string> descriptorCandidates(const std::string &xclbin_path)
{
  std::vector<std::string> candidates;
  if (const char *explicit_path = std::getenv("HARDCILK_HBM_DESCRIPTOR"))
    if (*explicit_path)
      candidates.push_back(explicit_path);

  if (!xclbin_path.empty())
  {
    std::string matching = xclbin_path;
    if (endsWith(matching, ".xclbin"))
      matching.resize(matching.size() - std::string(".xclbin").size());
    matching += ".hbmports.json";
    candidates.push_back(matching);

    const std::string name = basenameWithoutXclbin(xclbin_path);
    const std::string dir = dirname(xclbin_path);
    candidates.push_back(dir + "/../" + name + ".hbmports.json");
  }
  return candidates;
}

} // namespace hardcilk_rama_detail

inline RamaHostMapping loadRamaHostMapping(const std::string &xclbin_path,
                                           const std::string &runtime)
{
  RamaHostMapping result;
  const std::vector<std::string> candidates =
      hardcilk_rama_detail::descriptorCandidates(xclbin_path);
  for (const std::string &path : candidates)
  {
    const std::string json = hardcilk_rama_detail::readFile(path);
    if (json.empty())
      continue;

    const std::string host_mapping =
        hardcilk_rama_detail::jsonObject(json, "hostMapping");
    if (host_mapping.empty())
      continue;

    std::string mode;
    if (!hardcilk_rama_detail::jsonString(host_mapping, "mode", mode))
      throw std::runtime_error("Malformed RAMA hostMapping in " + path +
                               ": missing mode");
    result.descriptor_path = path;
    result.per_memory = mode == "per_memory";
    result.runtime_enabled =
        hardcilk_rama_detail::runtimeListed(host_mapping, runtime);

    if (!result.per_memory)
      return result;

    if (!hardcilk_rama_detail::jsonU64(host_mapping, "firstBank", result.first_bank) ||
        !hardcilk_rama_detail::jsonU64(host_mapping, "memoryCount", result.memory_count) ||
        !hardcilk_rama_detail::jsonU64(host_mapping, "fragmentBytes", result.fragment_bytes) ||
        !hardcilk_rama_detail::jsonU64(host_mapping, "bytesPerBank", result.bytes_per_bank))
      throw std::runtime_error("Malformed RAMA hostMapping in " + path +
                               ": missing numeric striping parameter");

    if (result.memory_count == 0 || result.fragment_bytes == 0 ||
        result.bytes_per_bank == 0 ||
        (result.memory_count & (result.memory_count - 1)) != 0 ||
        (result.fragment_bytes & (result.fragment_bytes - 1)) != 0)
      throw std::runtime_error("Invalid RAMA hostMapping parameters in " + path);
    return result;
  }
  return result;
}

inline void logRamaHostMapping(const RamaHostMapping &mapping,
                               const std::string &runtime)
{
  if (mapping.enabled())
  {
    std::cout << "[RAMA host] software per_memory mapping enabled for " << runtime
              << ": banks " << mapping.first_bank << ".."
              << (mapping.first_bank + mapping.memory_count - 1)
              << ", fragment=" << mapping.fragment_bytes
              << " bytes, bankBytes=" << mapping.bytes_per_bank
              << " (" << mapping.descriptor_path << ")\n";
  }
  else if (mapping.per_memory)
  {
    std::cout << "[RAMA host] descriptor requests per_memory striping, but it is "
                 "disabled for runtime "
              << runtime << " (" << mapping.descriptor_path << ")\n";
  }
}
