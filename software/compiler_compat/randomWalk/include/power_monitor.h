#pragma once

// Samples total board power via XRT and integrates it into energy (joules).
// Works on Alveo U55C (xilinx_u55c_gen3x16_xdma_base_3) with XRT native C++ API.
//
// Usage:
//   PowerMonitor mon(device);        // xrt::device, or PowerMonitor mon(0) by index
//   mon.start();
//   ... run kernel / managementLoop() ...
//   auto r = mon.stop();
//   std::cout << r.energy_joules << " J, avg " << r.avg_power_watts << " W\n";

#include <xrt/xrt_device.h>
#include <atomic>
#include <chrono>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

class PowerMonitor {
public:
  struct Result {
    double energy_joules = 0.0;
    double avg_power_watts = 0.0;
    double duration_seconds = 0.0;
    size_t num_samples = 0;
  };

  explicit PowerMonitor(xrt::device device, unsigned interval_ms = 100)
      : device_(std::move(device)), interval_ms_(interval_ms) {}

  explicit PowerMonitor(unsigned device_index = 0, unsigned interval_ms = 100)
      : device_(device_index), interval_ms_(interval_ms) {}

  ~PowerMonitor() {
    if (running_.load()) stop();
  }

  void start() {
    samples_.clear();
    running_.store(true);
    t_start_ = std::chrono::steady_clock::now();
    worker_ = std::thread([this] { this->sampleLoop(); });
  }

  Result stop() {
    running_.store(false);
    if (worker_.joinable()) worker_.join();
    auto t_end = std::chrono::steady_clock::now();

    Result r;
    r.duration_seconds =
        std::chrono::duration<double>(t_end - t_start_).count();
    r.num_samples = samples_.size();

    // Trapezoidal integration over (timestamp, watts) samples.
    for (size_t i = 1; i < samples_.size(); ++i) {
      double dt = samples_[i].t - samples_[i - 1].t;
      r.energy_joules += 0.5 * (samples_[i].w + samples_[i - 1].w) * dt;
    }
    if (r.duration_seconds > 0.0)
      r.avg_power_watts = r.energy_joules / r.duration_seconds;

    // Edge correction: extend first/last sample to the full window so short
    // head/tail gaps are covered.
    if (!samples_.empty()) {
      double head = samples_.front().t;                 // gap before 1st sample
      double tail = r.duration_seconds - samples_.back().t;
      r.energy_joules += head * samples_.front().w;
      r.energy_joules += (tail > 0 ? tail : 0) * samples_.back().w;
      r.avg_power_watts = r.energy_joules / r.duration_seconds;
    }
    return r;
  }

  // One-shot read, useful for measuring idle baseline before the run.
  double readPowerWatts() {
    std::string json = device_.get_info<xrt::info::device::electrical>();
    return parsePower(json);
  }

private:
  struct Sample { double t; double w; };

  void sampleLoop() {
    while (running_.load()) {
      double w = -1.0;
      try {
        w = readPowerWatts();
      } catch (...) {
        // sensor read failed this round; skip sample
      }
      if (w >= 0.0) {
        double t = std::chrono::duration<double>(
                       std::chrono::steady_clock::now() - t_start_).count();
        samples_.push_back({t, w});
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(interval_ms_));
    }
  }

  // Extracts "power_consumption_watts": "28.932736" from the electrical JSON
  // without pulling in a JSON library.
  static double parsePower(const std::string &json) {
    const std::string key = "\"power_consumption_watts\"";
    size_t pos = json.find(key);
    if (pos == std::string::npos)
      throw std::runtime_error("power_consumption_watts not found in report");
    pos = json.find(':', pos);
    pos = json.find_first_of("0123456789.", pos);
    size_t end = json.find_first_not_of("0123456789.", pos);
    return std::stod(json.substr(pos, end - pos));
  }

  xrt::device device_;
  unsigned interval_ms_;
  std::atomic<bool> running_{false};
  std::thread worker_;
  std::vector<Sample> samples_;
  std::chrono::steady_clock::time_point t_start_;
};