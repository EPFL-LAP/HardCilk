/**
 * @file reference_counted.hpp
 * @author Canberk Sönmez (canberk.sonmez.409@gmail.com)
 * @brief Utility classes for reference counted objects.
 * @date 2022-08-23
 *
 * Copyright (c) Canberk Sönmez 2022
 *
 */

#ifndef SYSC_NETW_UTIL_REFERENCE_COUNTED_HPP_INCLUDED
#define SYSC_NETW_UTIL_REFERENCE_COUNTED_HPP_INCLUDED

#include <cassert>
#include <type_traits>

namespace sysc_netw::util {

/**
 * @brief Makes a type reference counted (not thread safe).
 *
 * @tparam Derived CRTP parameter.
 */
template<typename Derived>
struct reference_counted_base {
protected:
    reference_counted_base() = default;

    reference_counted_base(const reference_counted_base&) = delete;
    reference_counted_base& operator=(const reference_counted_base&) = delete;

    reference_counted_base(reference_counted_base&&) = delete;
    reference_counted_base& operator=(reference_counted_base&&) = delete;

public:
    void ref() const noexcept {
        ++count_;
    }

    void unref() const {
        assert(count_ > 0 && "object is already destroyed.");
        if (--count_ == 0) {
            delete static_cast<Derived const*>(this);
        }
    }

    std::size_t ref_count() const noexcept {
        return count_;
    }

#ifndef NDEBUG
    ~reference_counted_base() {
        assert(count_ == 0 && "object is destroyed while there are still references to it.");
    }
#endif

private:
    mutable std::size_t count_ = 0;
};

/**
 * @brief Pointer type to be used with reference counted objects.
 *
 * @tparam T
 */
template<typename T>
struct ptr {
    ptr() noexcept = default;

    ptr(T* p) noexcept
        : ptr_ { p } {
        if (ptr_)
            ptr_->ref();
    }

    ptr(const ptr& other) noexcept {
        // noexcept, initially nullptr
        *this = other;
    }

    ptr& operator=(const ptr& other) {
        if (ptr_ != other.ptr_) {
            if (ptr_)
                ptr_->unref();
            ptr_ = other.ptr_;
            if (ptr_)
                ptr_->ref();
        }
        return *this;
    }

    ptr(ptr&& other) noexcept {
        *this = std::move(other);
    }

    ptr& operator=(ptr&& other) noexcept {
        std::swap(ptr_, other.ptr_);
        return *this;
    }

    T* operator->() noexcept {
        return ptr_;
    }

    T* operator->() const noexcept {
        return ptr_;
    }

#define REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP(OPNAME)      \
    bool operator OPNAME(ptr const& other) const noexcept { \
        return ptr_ OPNAME other.ptr_;                      \
    }

    REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP(==)
    REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP(!=)
    REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP(>)
    REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP(>=)
    REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP(<)
    REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP(<=)

#undef REFERENCE_COUNTED_PTR_IMPLEMENT_REL_OP

    bool valid() const noexcept {
        return ptr_ != nullptr;
    }

    operator bool() const noexcept {
        return valid();
    }

    bool operator==(std::nullptr_t) {
        return !valid();
    }

    T* get() noexcept {
        return ptr_;
    }

    T const* get() const noexcept {
        return ptr_;
    }

    operator T*() noexcept {
        return get();
    }

    operator T const*() const noexcept {
        return get();
    }

    template<typename U>
    ptr<U> cast() noexcept {
        return { static_cast<U*>(ptr_) };
    }

    template<typename U>
    ptr<const U> cast() const noexcept {
        return { static_cast<U const*>(ptr_) };
    }

    template<typename U>
    ptr<U> dyncast() noexcept {
        return { dynamic_cast<U*>(ptr_) };
    }

    template<typename U>
    ptr<const U> dyncast() const noexcept {
        return { dynamic_cast<U const*>(ptr_) };
    }

    ptr<const T> as_const() const noexcept {
        return { (const T*)ptr_ };
    }

    ~ptr() {
        if (ptr_)
            ptr_->unref();
    }

private:
    T* ptr_ = nullptr;
};

} /* namespace sysc_netw::util */

#include <functional>

namespace std {
template<typename T>
struct hash<sysc_netw::util::ptr<T>> : std::hash<const T*> {
    auto operator()(const sysc_netw::util::ptr<T>& x) const {
        return std::hash<const T*>::operator()(x.get());
    }
};
} /* namespace std */

#endif /* SYSC_NETW_UTIL_REFERENCE_COUNTED_HPP_INCLUDED */
