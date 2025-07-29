#pragma once

#include <functional>
#ifndef BLOCK_SIZE
#define BLOCK_SIZE 64
#endif

#include <cilk/cilk.h>
#include <cstddef>
#include <iostream>
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
  Sequence(int size) : data(size) {}
  Sequence(int size, const T &value) {
    data.resize(size);
    map([&](T &item, int, int) { item = value; });
  }
  Sequence(int size, std::function<T(int)> fn) {
    data.resize(size);
    map([&](T &item, int index, int) { item = fn(index); });
  }

  // Delete copy constructor and assignment operator
  Sequence(const Sequence &) = default;
  Sequence &operator=(const Sequence &) = default;

  // Move constructor and assignment operator
  Sequence(Sequence &&) = default;
  Sequence &operator=(Sequence &&) = default;

  void map(std::function<void(T &, int, int)> fn, int block_size = BLOCK_SIZE,
           int start = 0, int end = -1, int stride = 1) {
    if (end == -1)
      end = data.size();
    if ((end - start) / stride < block_size) {
      for (int i = start; i < end; i += stride) {
        fn(data[i], i, start * stride / block_size);
      }
    } else {
      int mid = (start + end) / 2;
      // Mid has to be a multiple of stride
      if ((mid - start) % stride != 0) {
        mid += stride - ((mid - start) % stride);
      }
      cilk_scope {
        cilk_spawn map(fn, block_size, start, mid, stride);
        cilk_spawn map(fn, block_size, mid, end, stride);
        cilk_sync;
      }
    }
  }

  T reduce(Monoid<T> monoid, int block_size = BLOCK_SIZE, int start = 0,
           int end = -1) {
    if (end == -1)
      end = data.size();
    if (end - start < block_size) {
      T result = monoid.initial_value;
      for (int i = start; i < end; i++) {
        result = monoid.combine(result, data[i]);
      }
      return result;
    }
    // Divide and conquer
    int mid = (start + end) / 2;
    T left_result = cilk_spawn reduce(monoid, block_size, start, mid);
    T right_result = cilk_spawn reduce(monoid, block_size, mid, end);
    cilk_sync;
    return monoid.combine(left_result, right_result);
  }

  Sequence<T> subset(std::function<bool(T)> filter) {
    Sequence<bool> marked(data.size());
    Sequence<int> marked_count(data.size() / BLOCK_SIZE + 1, 0);
    // Mark and count how many vertices are marked in each worker
    map([&](T vertex, int index, int work_id) {
      marked.set(index, filter(vertex));
      if (marked.get(index)) {
        marked_count[work_id]++;
      }
    });
    // Compute the prefix sum of marked counts to determine the buffer size for
    // each worker
    marked_count.inclusive_scan_inplace();
    Sequence<T> result(marked_count.back());
    // Fill the result sequence with the marked vertices, each worker writes to
    // its own part of the result
    map([&](T vertex, int index, int work_id) {
      if (marked.get(index)) {
        result[marked_count[work_id] - 1] = vertex;
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

  void inclusive_scan_serial_inplace(int stride) {
    for (int i = 2 * stride - 1; i < data.size(); i += stride) {
      data[i] = data[i - stride] + data[i];
    };
  }

  void inclusive_scan_inplace(int block_size = BLOCK_SIZE, int stride = 1) {
    // If the data size is smaller than the block size, use serial scan
    if (data.size() / stride < block_size) {
      return inclusive_scan_serial_inplace(stride);
    }

    // Divide and conquer
    // Step 1: Compute pairwise sums and assign to the right element
    map(
        [&](T &item, int index, int) {
          item += data[index - stride];
        },
        block_size, 2 * stride - 1, data.size(), 2 * stride);

    // Step 2: Recursive inclusive scan on the pairwise sums
    inclusive_scan_inplace(block_size, stride * 2);

    // Step 3: Fill left elements with the scanned values
    map([&](T &item, int index, int) { item += data[index - stride]; },
        block_size, 3 * stride - 1, data.size(), 2 * stride);

  }

  void resize(int new_size) { data.resize(new_size); }

  void push_back(const T &value) { data.push_back(value); }

  int size() const { return data.size(); }

  auto begin() { return data.begin(); }

  auto end() { return data.end(); }

  T &back() { return data.back(); }

  T &operator[](int index) { return data[index]; }

  void set(int index, const T &value) { data[index] = value; }

  T get(int index) const { return data[index]; }

private:
  std::vector<T> data;
};
