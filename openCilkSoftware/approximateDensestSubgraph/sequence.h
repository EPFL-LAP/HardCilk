#pragma once

#ifndef BLOCK_SIZE
#define BLOCK_SIZE 64
#endif

#include <cilk/cilk.h>
#include <cstddef>
#include <vector>

template <typename T> class Monoid {
public:
  Monoid(T initial_value, std::function<T(T, T)> combine)
      : initial_value(initial_value), combine(combine) {}
  T initial_value;
  std::function<T(T, T)> combine;
};

template <typename T> class Sequence {
public:
  Sequence() = default;
  Sequence(size_t size) : data(size) {}
  Sequence(size_t size, const T &value) {
    data.resize(size);
    map([&](T &item, size_t) { item = value; });
  }
  Sequence(size_t size, std::function<T(size_t)> fn) {
    data.resize(size);
    map([&](T &item, size_t index) { item = fn(index); });
  }

  // Delete copy constructor and assignment operator
  Sequence(const Sequence &) = delete;
  Sequence &operator=(const Sequence &) = delete;

  // Move constructor and assignment operator
  Sequence(Sequence &&) = default;
  Sequence &operator=(Sequence &&) = default;

  void map(std::function<void(T &, size_t, size_t)> fn,
           size_t block_size = BLOCK_SIZE, size_t start = 0, size_t end = -1) {
    if (end == -1)
      end = data.size();
    if (end - start < block_size) {
      for (size_t i = start; i < end; i++) {
        fn(data[i], i, start / block_size);
      }
    } else {
      size_t mid = (start + end) / 2;
      cilk_spawn map(fn, block_size, start, mid);
      cilk_spawn map(fn, block_size, mid, end);
      cilk_sync;
    }
  }

//   template <typename R>
//   Sequence<R> map(std::function<R(const T &, size_t, size_t)> fn,
//                   Monoid<R> reduce_fn, size_t block_size = BLOCK_SIZE,
//                   size_t start = 0, size_t end = -1) {
//     if (end == -1)
//       end = data.size();
//     if (end - start < block_size) {

//       for (size_t i = start; i < end; i++) {
//       }
//     }
//   }

  Sequence<T> inclusive_scan_serial() {
    Sequence<T> result(data.size());
    if (data.empty())
      return result;

    result[0] = data[0];
    for (size_t i = 1; i < data.size(); ++i) {
      result[i] = result[i - 1] + data[i];
    }
    return result;
  }

  Sequence<T> inclusive_scan(size_t block_size = BLOCK_SIZE) {
    // If the data size is smaller than the block size, use serial scan
    if (data.size() < block_size) {
      return inclusive_scan_serial();
    }

    // Divice and conquer
    // Step 1: Compute pairwise sums
    Sequence<T> sums(data.size() / 2);
    sums.map([&](T &item, size_t index, size_t work_id) {
      item = data[2 * index] + data[2 * index + 1];
    });

    // Step 2: Recursive inclusive scan on the pairwise sums
    Sequence<T> scanned = sums.inclusive_scan(block_size);

    // Step 3: Fill output using scanned sums
    Sequence<T> result(data.size());
    scanned.map([&](T &item, size_t index, size_t work_id) {
      result[2 * index] =
          (index == 0) ? data[0] : scanned[index - 1] + data[2 * index];
      result[2 * index + 1] = scanned[index];
    });

    // Handle odd element at the end (if data size is odd)
    if (data.size() % 2 != 0) {
      result[data.size() - 1] = result[data.size() - 2] + data[data.size() - 1];
    }

    return result;
  }

  void resize(size_t new_size) { data.resize(new_size); }

  void push_back(const T &value) { data.push_back(value); }

  size_t size() const { return data.size(); }

  auto begin() { return data.begin(); }

  auto end() { return data.end(); }

  T &back() { return data.back(); }

  T &operator[](size_t index) { return data[index]; }

private:
  std::vector<T> data;
};
