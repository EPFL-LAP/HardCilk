#include "../sequence.h"
#include <algorithm>
#include <cassert>
#include <random>

int main() {
  std::srand(static_cast<unsigned int>(
      time(nullptr))); // Seed for random number generation
  // std::srand(42); // Fixed seed for reproducibility
  const int size = 20000;
  Sequence<std::pair<int, int>> random_sequence(
      size, [](int idx) { return std::make_pair(std::rand() % 1000, 1); });

  Sequence<std::pair<int, int>> sorted_sequence = random_sequence.clone();
  std::stable_sort(
      sorted_sequence.begin(), sorted_sequence.end(),
      [](const std::pair<int, int> &a, const std::pair<int, int> &b) {
        return a.first < b.first;
      });

  // Create histogram of the sorted sequence
  Sequence<std::pair<int, int>> expected_sequence = histogram_sequential(
      sorted_sequence, Monoid<int>(0, [](int a, int b) { return a + b; }));
  Sequence<std::pair<int, int>> res = histogram(
      sorted_sequence, Monoid<int>(0, [](int a, int b) { return a + b; }));

  if (res.size() != expected_sequence.size()) {
    std::cerr << "Size mismatch: expected " << expected_sequence.size()
              << ", got " << res.size() << std::endl;
    std::cerr << "Expected sequence: \n";
    for (const auto &pair : expected_sequence) {
      std::cerr << pair.first << " -- " << pair.second << std::endl;
    }
    std::cerr << "Result sequence: \n";
    for (const auto &pair : res) {
      std::cerr << pair.first << " -- " << pair.second << std::endl;
    }
    std::cerr << "Histogram test failed!" << std::endl;
    return 1;
  }
  for (int i = 0; i < res.size(); ++i) {
    if (res[i].first != expected_sequence[i].first ||
        res[i].second != expected_sequence[i].second) {
      std::cerr << "Mismatch at index " << i << ": " << res[i].first << " -- "
                << res[i].second << " != " << expected_sequence[i].first
                << " -- " << expected_sequence[i].second << std::endl;
      return 1;
    }
  }
  std::cout << "Histogram test passed!" << std::endl;
  return 0;
}