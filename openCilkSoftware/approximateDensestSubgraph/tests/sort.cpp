#include "../sequence.h"
#include <algorithm>
#include <cassert>
#include <random>

int main() {
  std::srand(static_cast<unsigned int>(
      time(nullptr))); // Seed for random number generation
  // std::srand(42); // Fixed seed for reproducibility
  const int size = 20000;
  Sequence<int> random_sequence(size, [](int idx) {
    return std::rand() %
           1000; // Example sequence: random numbers between 0 and 999
  });

  Sequence<int> expected_sequence = random_sequence.clone();
  std::stable_sort(expected_sequence.begin(), expected_sequence.end(),
                   [](int a, int b) { return a < b; });

  auto res = random_sequence.sort([](const int &a) { return a; }, 12, 1, 4, 5);
  int i;
  for (i = 0; i < size; ++i) {
    if (res[i] != expected_sequence[i]) {
      std::cerr << "Mismatch at index " << i << ": " << res[i]
                << " != " << expected_sequence[i] << std::endl;
      break;
    }
  }
  if (i == size) {
    std::cout << "Inclusive scan test passed!" << std::endl;
  } else {
    std::cout << "Inclusive scan test failed!" << std::endl;
    std::cout << "Result sequence: \n";
    res.print();
    std::cout << "Expected sequence: \n";
    expected_sequence.print();
  }
}