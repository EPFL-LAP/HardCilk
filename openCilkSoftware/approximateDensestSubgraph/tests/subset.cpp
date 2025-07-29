#include "../sequence.h"
#include <cassert>
#include <random>

int main() {
    std::srand(static_cast<unsigned int>(time(nullptr))); // Seed for random number generation
    const long long size = 50000;
    Sequence<long long> random_sequence(size, [](long long idx) {
        return std::rand() % 100; // Example sequence: random numbers between 0 and 99
    });

    Sequence<long long> expected_sequence;
    for (long long i = 0; i < size; ++i) {
        if (random_sequence[i] < 50) {
            expected_sequence.push_back(random_sequence[i]);
        }
    }

    auto res = random_sequence.subset([&](long long item) {
        return item < 50;
    });
    long long i;
    for (i = 0; i < res.size(); ++i) {
        if (res[i] != expected_sequence[i]) {
            std::cerr << "Mismatch at index " << i << ": "
                      << res[i] << " != " << expected_sequence[i]
                      << std::endl;
            break;
        }
    }
    if (i == res.size()) {
        std::cout << "Subset test passed!" << std::endl;
    } else {
        std::cout << "Subset test failed!" << std::endl;
        std::cout << "Result sequence: \n";
        res.print();
        std::cout << "Expected sequence: \n";
        expected_sequence.print();
    }
}