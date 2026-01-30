#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

// --- Lock-Free Ring Buffer (SPSC) ---
// Single Producer (Capture), Single Consumer (Bridge)
class RingBuffer {
public:
    RingBuffer(size_t size_bytes) : size_(size_bytes), head_(0), tail_(0) { buffer_.resize(size_); }

    size_t write(const uint8_t* data, size_t count) {
        size_t current_tail = tail_.load(std::memory_order_acquire);
        size_t available = size_ - (head_.load(std::memory_order_relaxed) - current_tail);

        if (count > available) return 0;

        size_t write_idx = head_.load(std::memory_order_relaxed) % size_;
        size_t first_chunk = std::min(count, size_ - write_idx);

        memcpy(&buffer_[write_idx], data, first_chunk);
        if (first_chunk < count) {
            memcpy(&buffer_[0], data + first_chunk, count - first_chunk);
        }

        head_.fetch_add(count, std::memory_order_release);
        return count;
    }

    size_t read(uint8_t* dest, size_t count) {
        size_t current_head = head_.load(std::memory_order_acquire);
        size_t available = current_head - tail_.load(std::memory_order_relaxed);

        if (available < count) return 0;  // Wait for full burst

        size_t read_idx = tail_.load(std::memory_order_relaxed) % size_;
        size_t first_chunk = std::min(count, size_ - read_idx);

        memcpy(dest, &buffer_[read_idx], first_chunk);
        if (first_chunk < count) {
            memcpy(dest + first_chunk, &buffer_[0], count - first_chunk);
        }

        tail_.fetch_add(count, std::memory_order_release);
        return count;
    }

    size_t available() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_relaxed);
    }

private:
    std::vector<uint8_t> buffer_;
    size_t size_;
    std::atomic<size_t> head_;
    std::atomic<size_t> tail_;
};

#endif  // RING_BUFFER_H
