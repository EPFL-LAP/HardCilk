#include "../graph.h"
#include <algorithm>
#include <cassert>
#include <random>

int main() {
    auto seed = static_cast<unsigned int>(time(nullptr)); // Seed for random number generation
    // auto seed = 42; // Fixed seed for reproducibility
    std::srand(seed); // Seed for random number generation
    const int size = 20000;
    auto degrees = Sequence<int>(size, [](int idx) {
        return std::rand() % 100; // Example sequence: random numbers between 0 and 99
    });
    int expected_sum = 0;
    for (int i = 0; i < size; ++i) {
        expected_sum += degrees[i];
    }
    auto res = degrees.reduce(Monoid<int>(0, [](int a, int b) { return a + b; }));
    assert(res == expected_sum);
    std::cout << "Reduction test passed! " << std::endl;
}