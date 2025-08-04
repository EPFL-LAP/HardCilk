#pragma once

#include <cmath>
#include <functional>
#include <initializer_list>
#include <utility>
#ifndef BLOCK_SIZE
#define BLOCK_SIZE 64
#endif

#include <cilk/cilk.h>
#include <cstddef>
#include <iostream>
#include <vector>

int powi(int base, unsigned int exp) {
  int res = 1;
  while (exp) {
    if (exp & 1)
      res *= base;
    exp >>= 1;
    base *= base;
  }
  return res;
}

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

  Sequence(const std::initializer_list<T> &list) {
    data.reserve(list.size());
    map([&](T &item, int index, int) {
      item = *(list.begin() + index);
    });
  }

  // Delete copy constructor and assignment operator
  Sequence(const Sequence &) = default;
  Sequence &operator=(const Sequence &) = default;

  // Move constructor and assignment operator
  Sequence(Sequence &&) = default;
  Sequence &operator=(Sequence &&) = default;

  /**
   * @brief Sort the sequence using a parallel bucket sort algorithm.
   *
   * @param comparator A function to get int for element to be used in radix sort.
   * @param total_iter The total number of iterations for the sorting process.
   * @param iter_count The current iteration count (default is 1).
   * @param bucket_count The number of buckets to use for sorting (default is
   * 4).
   * @param block_size The size of each block to process (default is
   * BLOCK_SIZE).
   * @return Sequence<T> The sorted sequence.
   */
  Sequence<T> sort(std::function<int(const T &)> comparator, int total_iter,
                   int iter_count = 1, int bucket_count = 4,
                   int block_size = BLOCK_SIZE) {
    Sequence<T> sorted_sequence(data.size());
    int work_cnt = data.size() / block_size + 2;
    Sequence<int> cnts(work_cnt * bucket_count, 0);
    map(
        [&](const T &item, int index, int work_id) {
          int bucket_index =
              (comparator(item) / (powi(bucket_count, (iter_count - 1)))) % bucket_count;
          cnts[bucket_index * work_cnt + work_id + 1]++;
        },
        block_size);
    cnts.inclusive_scan_inplace(block_size);
    map(
        [&](const T &item, int index, int work_id) {
          int bucket_index =
              (comparator(item) / (powi(bucket_count, (iter_count - 1)))) % bucket_count;
          int position = cnts[bucket_index * work_cnt + work_id]++;
          sorted_sequence[position] = item;
        },
        block_size);
    if (iter_count < total_iter) {
      return sorted_sequence.sort(comparator, total_iter, iter_count + 1,
                                  bucket_count, block_size);
    }
    return sorted_sequence;
  }

  void print() const {
    for (const auto &item : data) {
      std::cout << item << " ";
    }
    std::cout << std::endl;
  }

  void map_serial(std::function<void(T &, int, int)> fn, int begin, int end,
                  int work_id, int stride) {
    for (int i = begin; i < end; i += stride) {
      fn(data[i], i, work_id);
    }
  }

  void map(std::function<void(T &, int, int)> fn, int block_size = BLOCK_SIZE,
           int stride = 1, int skip_begin = 0, int skip_end = 0) {
    auto work_count =
        (data.size() + block_size - 1 - skip_end - skip_begin) / block_size;
    for (int work_id = 0; work_id < work_count; ++work_id) {
      int start = work_id * block_size * stride + skip_begin;
      int end =
          std::min(start + block_size * stride, (int)data.size() - skip_end);
      map_serial(fn, start, end, work_id, stride);
      // cilk_spawn map_serial(fn, start, end, work_id, stride);
    }
    // cilk_sync;
  }

  T reduce(Monoid<T> monoid, int block_size = BLOCK_SIZE, int start = 0,
           int end = -1) {
    if (end == -1)
      end = data.size();
    if (end - start <= block_size) {
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
    Sequence<int> marked_count(data.size() / BLOCK_SIZE + 2, 0);
    // Mark and count how many vertices are marked in each worker
    map([&](T vertex, int index, int work_id) {
      marked.set(index, filter(vertex));
      if (marked.get(index)) {
        marked_count[work_id + 1]++;
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
        result[marked_count[work_id]] = vertex;
        marked_count[work_id]++;
      }
    });
    return result;
  }

  Sequence<T> clone() const {
    Sequence<T> result(data.size(), [&](int idx) { return data[idx]; });
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
    map([&](T &item, int index, int) { item += data[index - stride]; },
        block_size, 2 * stride, 2 * stride - 1, 0);

    // Step 2: Recursive inclusive scan on the pairwise sums
    inclusive_scan_inplace(block_size, stride * 2);

    // Step 3: Fill left elements with the scanned values
    map([&](T &item, int index, int) { item += data[index - stride]; },
        block_size, 2 * stride, 3 * stride - 1, 0);
  }

  void resize(int new_size) { data.resize(new_size); }

  void push_back(const T &value) { data.push_back(value); }

  int size() const { return data.size(); }

  auto begin() { return data.begin(); }

  auto end() { return data.end(); }

  T &back() { return data.back(); }

  T &front() { return data.front(); }

  void insert_end(std::vector<T>::iterator first,
                  typename std::vector<T>::iterator last,
                  int block_size = BLOCK_SIZE) {
    if (last - first <= block_size) {
      data.insert(data.end(), first, last);
    } else {
      int n_blocks = (last - first + block_size - 1) / block_size;
      for (int i = 0; i < n_blocks; ++i) {
        int block_start = i * block_size;
        int block_end = std::min(block_start + block_size, (int)(last - first));
        cilk_spawn data.insert(data.end(), first + block_start,
                               first + block_end);
      }
      cilk_sync;
    }
  }

  bool empty() const { return data.empty(); }

  T &operator[](int index) { return data[index]; }

  void set(int index, const T &value) { data[index] = value; }

  T get(int index) const { return data[index]; }

private:
  std::vector<T> data;
};

/**
 * @brief Assumes the input is sorted based on the first element of the pair.
 * Combines the same keys using the provided monoid.
 *
 * @return Sequence<std::pair<T, M>>: A sequence of pairs where the first
 * element is unique and the second element is the reduced value.
 */
template <typename T, typename M>
Sequence<std::pair<T, M>> histogram_sequential(Sequence<std::pair<T, M>> &input,
                                               Monoid<M> monoid, int start = 0,
                                               int end = -1) {
  if (end == -1)
    end = input.size();
  Sequence<std::pair<T, M>> result;
  int i = start;
  while (i < end) {
    T key = input[i].first;
    M value = input[i].second;
    while (i + 1 < end && input[i + 1].first == key) {
      value = monoid.combine(value, input[i + 1].second);
      i++;
    }
    result.push_back({key, value});
    i++;
  }
  return result;
}

/**
 * @brief Assumes the input is sorted based on the first element of the pair.
 * Combines the same keys using the provided monoid.
 *
 * @return Sequence<std::pair<T, M>>: A sequence of pairs where the first
 * element is unique and the second element is the reduced value.
 */
template <typename T, typename M>
Sequence<std::pair<T, M>>
histogram(Sequence<std::pair<T, M>> &input, Monoid<M> monoid,
          int block_size = BLOCK_SIZE, int start = 0, int end = -1) {
  if (end == -1)
    end = input.size();
  if (end - start <= block_size) {
    return histogram_sequential(input, monoid, start, end);
  }
  // Divide and conquer
  int n_blocks = (end - start + block_size - 1) / block_size;
  Sequence<Sequence<std::pair<T, M>>> partial_results(n_blocks);
  for (int i = 0; i < n_blocks; ++i) {
    int block_start = start + i * block_size;
    int block_end = std::min(block_start + block_size, end);
    cilk_spawn partial_results[i] =
        histogram_sequential(input, monoid, block_start, block_end);
  }
  cilk_sync;
  Sequence<std::pair<T, M>> result =
      partial_results.reduce(Monoid<Sequence<std::pair<T, M>>>(
          Sequence<std::pair<T, M>>(),
          [&](Sequence<std::pair<T, M>> a, Sequence<std::pair<T, M>> b) {
            if (a.empty())
              return b;
            if (b.empty())
              return a;
            // Merge two sorted sequences
            if (a.back().first == b.front().first) {
              a.back().second =
                  monoid.combine(a.back().second, b.front().second);
              a.insert_end(b.begin() + 1, b.end(), block_size);
            } else {
              a.insert_end(b.begin(), b.end(), block_size);
            }
            return a;
          }));
  return result;
}
