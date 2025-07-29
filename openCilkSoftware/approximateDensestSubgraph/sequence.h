#pragma once

#include <functional>
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
    map([&](T &item, size_t, size_t) { item = value; });
  }
  Sequence(size_t size, std::function<T(size_t)> fn) {
    data.resize(size);
    map([&](T &item, size_t index, size_t) { item = fn(index); });
  }

  // Delete copy constructor and assignment operator
  Sequence(const Sequence &) = default;
  Sequence &operator=(const Sequence &) = default;

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

  T reduce(Monoid<T> monoid, size_t block_size = BLOCK_SIZE, 
           size_t start = 0, size_t end = -1) {
    if (end == -1)
      end = data.size();
    if (end - start < block_size) {
      T result = monoid.initial_value;
      for (size_t i = start; i < end; i++) {
        result = monoid.combine(result, data[i]);
      }
      return result;
    }
    // Divide and conquer
    size_t mid = (start + end) / 2;
    T left_result = cilk_spawn reduce(monoid, block_size, start, mid);
    T right_result = cilk_spawn reduce(monoid, block_size, mid, end);
    cilk_sync;
    return monoid.combine(left_result, right_result);
  }

  Sequence<T> subset(std::function<bool(T)> filter) {
    Sequence<bool> marked(data.size());
    Sequence<size_t> marked_count(data.size() / BLOCK_SIZE + 1, 0);
    // Mark and count how many vertices are marked in each worker
    map([&](T vertex, size_t index, size_t work_id) {
      marked.set(index, filter(vertex));
      if (marked.get(index)) {
        marked_count[work_id]++;
      }
    });
    // Compute the prefix sum of marked counts to determine the buffer size for
    // each worker
    Sequence<size_t> marked_count_inclusive = marked_count.inclusive_scan();
    Sequence<T> result(marked_count_inclusive.back());
    // Fill the result sequence with the marked vertices, each worker writes to
    // its own part of the result
    map([&](T vertex, size_t index, size_t work_id) {
      if (marked.get(index)) {
        result[marked_count_inclusive[work_id] - 1] = vertex;
        marked_count[work_id]--;
      }
    });
    return result;
  }

  Sequence<T> clone() const {
    Sequence<T> result(data.size());
    result.data = data;
    return result;
  }

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

  void set(size_t index, const T &value) {
    data[index] = value;
  }

  T get(size_t index) const {
    return data[index];
  }

private:
  std::vector<T> data;
};
