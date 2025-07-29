#include "../sequence.h"
#include <cassert>
#include <random>

int main() {
    std::srand(static_cast<unsigned int>(time(nullptr))); // Seed for random number generation
    const long long size = 50000;
    Sequence<long long> random_sequence(size, [](long long idx) {
        return std::rand() % 100; // Example sequence: random numbers between 0 and 99
    });

    Sequence<long long> expected_sequence(size);
    long long sum = 0;
    for (long long i = 0; i < size; ++i) {
        sum += random_sequence[i];
        expected_sequence[i] = sum;
    }

    random_sequence.inclusive_scan_inplace(5);
    long long i;
    for (i = 0; i < size; ++i) {
        if (random_sequence[i] != expected_sequence[i]) {
            std::cerr << "Mismatch at index " << i << ": "
                      << random_sequence[i] << " != " << expected_sequence[i]
                      << std::endl;
            break;
        }
    }
    if (i == size) {
        std::cout << "Inclusive scan test passed!" << std::endl;
    } else {
        std::cout << "Inclusive scan test failed!" << std::endl;
        std::cout << "Random sequence: \n";
        random_sequence.print();
        std::cout << "Expected sequence: \n";
        expected_sequence.print();
    }
}