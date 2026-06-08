#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <set>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <setjmp.h>

#define LOG_TAG "XHH-DexMemoryNative"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct MapRegion {
    uintptr_t start = 0;
    uintptr_t end = 0;
    std::string perms;
    std::string pathname;
};

struct DexHit {
    uintptr_t address = 0;
    uintptr_t mapStart = 0;
    uintptr_t mapEnd = 0;
    uint32_t fileSize = 0;
    std::string version;
    std::string mapPerms;
    std::string mapPath;
    std::string dumpPath;
};

static sigjmp_buf g_jmp;
static volatile sig_atomic_t g_in_safe_copy = 0;
static struct sigaction g_old_segv;
static struct sigaction g_old_bus;

static void signal_handler(int sig, siginfo_t*, void*) {
    if (g_in_safe_copy) {
        siglongjmp(g_jmp, sig);
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_signal_handlers() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigaction(SIGSEGV, &sa, &g_old_segv);
    sigaction(SIGBUS, &sa, &g_old_bus);
}

static void restore_signal_handlers() {
    sigaction(SIGSEGV, &g_old_segv, nullptr);
    sigaction(SIGBUS, &g_old_bus, nullptr);
}

static bool safe_copy(void* dst, const void* src, size_t size) {
    if (dst == nullptr || src == nullptr) return false;
    if (size == 0) return true;
    if (sigsetjmp(g_jmp, 1) != 0) {
        g_in_safe_copy = 0;
        return false;
    }
    g_in_safe_copy = 1;
    memcpy(dst, src, size);
    g_in_safe_copy = 0;
    return true;
}

static uint32_t read_u32_le(const uint8_t* p) {
    return (uint32_t)p[0]
         | ((uint32_t)p[1] << 8)
         | ((uint32_t)p[2] << 16)
         | ((uint32_t)p[3] << 24);
}

static uint16_t read_u16_le(const uint8_t* p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static bool safe_read_bytes(uintptr_t base, uint32_t fileSize, uint32_t off, void* out, size_t size) {
    if (out == nullptr) return false;
    if (off > fileSize || size > fileSize || off + size > fileSize) return false;
    return safe_copy(out, reinterpret_cast<const void*>(base + off), size);
}

static bool safe_read_u32_at(uintptr_t base, uint32_t fileSize, uint32_t off, uint32_t& out) {
    uint8_t buf[4];
    if (!safe_read_bytes(base, fileSize, off, buf, sizeof(buf))) return false;
    out = read_u32_le(buf);
    return true;
}

static bool safe_read_u16_at(uintptr_t base, uint32_t fileSize, uint32_t off, uint16_t& out) {
    uint8_t buf[2];
    if (!safe_read_bytes(base, fileSize, off, buf, sizeof(buf))) return false;
    out = read_u16_le(buf);
    return true;
}

static bool valid_table_bounds(uint32_t fileSize, uint32_t size, uint32_t off, uint32_t itemSize) {
    if (size == 0) return true;
    if (off == 0 || off >= fileSize) return false;
    uint64_t end = static_cast<uint64_t>(off) + static_cast<uint64_t>(size) * itemSize;
    return end <= fileSize;
}

static uint32_t fixed_map_item_size(uint16_t type) {
    switch (type) {
        case 0x0000: return 0x70; // header_item
        case 0x0001: return 4;    // string_id_item
        case 0x0002: return 4;    // type_id_item
        case 0x0003: return 12;   // proto_id_item
        case 0x0004: return 8;    // field_id_item
        case 0x0005: return 8;    // method_id_item
        case 0x0006: return 32;   // class_def_item
        default: return 1;        // variable-size data item: offset must still be inside file
    }
}

static bool validate_map_list_at(uintptr_t base, uint32_t fileSize, uint32_t mapOff) {
    if (mapOff == 0) return true;
    if (mapOff + 4 > fileSize) return false;
    uint32_t mapSize = 0;
    if (!safe_read_u32_at(base, fileSize, mapOff, mapSize)) return false;
    if (mapSize == 0 || mapSize > 4096) return false;
    uint64_t end = static_cast<uint64_t>(mapOff) + 4 + static_cast<uint64_t>(mapSize) * 12;
    if (end > fileSize) return false;
    uint32_t cursor = mapOff + 4;
    for (uint32_t i = 0; i < mapSize; ++i, cursor += 12) {
        uint16_t type = 0;
        uint32_t size = 0;
        uint32_t off = 0;
        if (!safe_read_u16_at(base, fileSize, cursor, type)) return false;
        if (!safe_read_u32_at(base, fileSize, cursor + 4, size)) return false;
        if (!safe_read_u32_at(base, fileSize, cursor + 8, off)) return false;
        if (size == 0) continue;
        uint32_t itemSize = fixed_map_item_size(type);
        if (type == 0x0000) {
            if (off != 0) return false;
        } else if (off == 0 || off >= fileSize) {
            return false;
        }
        uint64_t itemEnd = static_cast<uint64_t>(off) + static_cast<uint64_t>(size) * itemSize;
        if (itemEnd > fileSize) return false;
    }
    return true;
}

static bool validate_index_tables_at(uintptr_t base,
                                     uint32_t fileSize,
                                     uint32_t stringIdsSize, uint32_t stringIdsOff,
                                     uint32_t typeIdsSize, uint32_t typeIdsOff,
                                     uint32_t protoIdsSize, uint32_t protoIdsOff,
                                     uint32_t fieldIdsSize, uint32_t fieldIdsOff,
                                     uint32_t methodIdsSize, uint32_t methodIdsOff,
                                     uint32_t classDefsSize, uint32_t classDefsOff) {
    for (uint32_t i = 0; i < typeIdsSize; ++i) {
        uint32_t descriptorIdx = 0;
        if (!safe_read_u32_at(base, fileSize, typeIdsOff + i * 4, descriptorIdx)) return false;
        if (descriptorIdx >= stringIdsSize) return false;
    }
    for (uint32_t i = 0; i < protoIdsSize; ++i) {
        uint32_t shortyIdx = 0, returnTypeIdx = 0, parametersOff = 0;
        uint32_t off = protoIdsOff + i * 12;
        if (!safe_read_u32_at(base, fileSize, off, shortyIdx)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 4, returnTypeIdx)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 8, parametersOff)) return false;
        if (shortyIdx >= stringIdsSize || returnTypeIdx >= typeIdsSize) return false;
        if (parametersOff != 0 && parametersOff + 4 > fileSize) return false;
    }
    for (uint32_t i = 0; i < fieldIdsSize; ++i) {
        uint16_t classIdx = 0, typeIdx = 0;
        uint32_t nameIdx = 0;
        uint32_t off = fieldIdsOff + i * 8;
        if (!safe_read_u16_at(base, fileSize, off, classIdx)) return false;
        if (!safe_read_u16_at(base, fileSize, off + 2, typeIdx)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 4, nameIdx)) return false;
        if (classIdx >= typeIdsSize || typeIdx >= typeIdsSize || nameIdx >= stringIdsSize) return false;
    }
    for (uint32_t i = 0; i < methodIdsSize; ++i) {
        uint16_t classIdx = 0, protoIdx = 0;
        uint32_t nameIdx = 0;
        uint32_t off = methodIdsOff + i * 8;
        if (!safe_read_u16_at(base, fileSize, off, classIdx)) return false;
        if (!safe_read_u16_at(base, fileSize, off + 2, protoIdx)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 4, nameIdx)) return false;
        if (classIdx >= typeIdsSize || protoIdx >= protoIdsSize || nameIdx >= stringIdsSize) return false;
    }
    for (uint32_t i = 0; i < classDefsSize; ++i) {
        uint32_t classIdx = 0, superclassIdx = 0, interfacesOff = 0, sourceFileIdx = 0;
        uint32_t annotationsOff = 0, classDataOff = 0, staticValuesOff = 0;
        uint32_t off = classDefsOff + i * 32;
        if (!safe_read_u32_at(base, fileSize, off, classIdx)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 8, superclassIdx)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 12, interfacesOff)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 16, sourceFileIdx)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 20, annotationsOff)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 24, classDataOff)) return false;
        if (!safe_read_u32_at(base, fileSize, off + 28, staticValuesOff)) return false;
        if (classIdx >= typeIdsSize) return false;
        if (superclassIdx != 0xffffffffu && superclassIdx >= typeIdsSize) return false;
        if (sourceFileIdx != 0xffffffffu && sourceFileIdx >= stringIdsSize) return false;
        if (interfacesOff != 0 && interfacesOff + 4 > fileSize) return false;
        if (annotationsOff != 0 && annotationsOff >= fileSize) return false;
        if (classDataOff != 0 && classDataOff >= fileSize) return false;
        if (staticValuesOff != 0 && staticValuesOff >= fileSize) return false;
    }
    return true;
}

static bool validate_string_data_at(uintptr_t base, uint32_t fileSize, uint32_t off) {
    if (off == 0 || off >= fileSize) return false;
    uint32_t cursor = off;
    uint8_t b = 0;
    int shift = 0;
    do {
        if (cursor >= fileSize) return false;
        if (!safe_read_bytes(base, fileSize, cursor, &b, 1)) return false;
        cursor++;
        shift += 7;
        if (shift > 35) return false;
    } while ((b & 0x80) != 0);
    uint32_t scanned = 0;
    while (cursor < fileSize && scanned < 1024u * 1024u) {
        if (!safe_read_bytes(base, fileSize, cursor, &b, 1)) return false;
        cursor++;
        if (b == 0) return true;
        scanned++;
    }
    return false;
}

static bool validate_string_offsets_sample_at(uintptr_t base, uint32_t fileSize, uint32_t stringIdsSize, uint32_t stringIdsOff) {
    uint32_t count = std::min<uint32_t>(stringIdsSize, 65536u);
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t stringOff = 0;
        if (!safe_read_u32_at(base, fileSize, stringIdsOff + i * 4, stringOff)) return false;
        if (!validate_string_data_at(base, fileSize, stringOff)) return false;
    }
    return true;
}

static bool starts_with(const std::string& s, const std::string& prefix) {
    return s.size() >= prefix.size() && s.compare(0, prefix.size(), prefix) == 0;
}

static bool contains_one(const uint8_t* base, size_t size, const std::string& needle) {
    if (needle.empty()) return true;
    const char* n = needle.c_str();
    size_t nlen = needle.size();
    if (nlen > size) return false;
    for (size_t i = 0; i + nlen <= size; ++i) {
        if (memcmp(base + i, n, nlen) == 0) return true;
    }
    return false;
}

static void mark_required_matches(const uint8_t* base, size_t size, const std::vector<std::string>& needles, std::vector<bool>& matched) {
    if (needles.empty()) return;
    if (matched.size() != needles.size()) matched.assign(needles.size(), false);
    for (size_t idx = 0; idx < needles.size(); ++idx) {
        if (matched[idx]) continue;
        if (contains_one(base, size, needles[idx])) matched[idx] = true;
    }
}

static bool any_matched(const std::vector<bool>& matched) {
    for (bool v : matched) if (v) return true;
    return false;
}

static bool all_matched(const std::vector<bool>& matched) {
    for (bool v : matched) if (!v) return false;
    return true;
}

static std::vector<MapRegion> read_maps() {
    std::vector<MapRegion> out;
    std::ifstream in("/proc/self/maps");
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream iss(line);
        std::string range, perms, offset, dev, inode;
        if (!(iss >> range >> perms >> offset >> dev >> inode)) continue;
        size_t dash = range.find('-');
        if (dash == std::string::npos) continue;
        MapRegion r;
        r.start = static_cast<uintptr_t>(strtoull(range.substr(0, dash).c_str(), nullptr, 16));
        r.end = static_cast<uintptr_t>(strtoull(range.substr(dash + 1).c_str(), nullptr, 16));
        r.perms = perms;
        std::string rest;
        std::getline(iss, rest);
        while (!rest.empty() && (rest[0] == ' ' || rest[0] == '\t')) rest.erase(rest.begin());
        r.pathname = rest;
        if (r.start < r.end) out.push_back(r);
    }
    return out;
}

static bool should_scan_region(const MapRegion& r,
                               uint64_t maxRegionBytes,
                               bool includeAnonymous,
                               bool includeFileBacked) {
    if (r.perms.empty() || r.perms[0] != 'r') return false;
    uint64_t size = static_cast<uint64_t>(r.end - r.start);
    if (size < 0x70) return false;
    if (maxRegionBytes > 0 && size > maxRegionBytes) return false;

    bool anonymous = r.pathname.empty() || r.pathname[0] == '[';
    if (anonymous && !includeAnonymous) return false;
    if (!anonymous && !includeFileBacked) {
        // Keep app-private anonymous/ashmem-like mappings, but skip ordinary file mappings unless requested.
        if (!starts_with(r.pathname, "/data/")) return false;
    }
    // Skip this native library and common non-dex mappings unless file-backed scanning is explicitly enabled.
    if (!includeFileBacked) {
        if (r.pathname.find("/system/") != std::string::npos) return false;
        if (r.pathname.find("/apex/") != std::string::npos) return false;
        if (r.pathname.find(".so") != std::string::npos) return false;
        if (r.pathname.find(".art") != std::string::npos) return false;
        if (r.pathname.find(".oat") != std::string::npos) return false;
        if (r.pathname.find(".vdex") != std::string::npos) return false;
    }
    return true;
}

static bool validate_dex_header_at(uintptr_t address, uintptr_t regionEnd, uint32_t maxDumpBytes, bool relaxed, DexHit& hit) {
    uint8_t header[0x70];
    if (address + sizeof(header) > regionEnd) return false;
    if (!safe_copy(header, reinterpret_cast<const void*>(address), sizeof(header))) return false;

    if (!(header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n')) return false;
    if (!(header[4] == '0' && (header[5] == '3' || header[5] == '4') && header[7] == 0)) return false;

    uint32_t fileSize = read_u32_le(header + 32);
    uint32_t headerSize = read_u32_le(header + 36);
    uint32_t endianTag = read_u32_le(header + 40);
    uint32_t mapOff = read_u32_le(header + 52);
    uint32_t stringIdsSize = read_u32_le(header + 56);
    uint32_t stringIdsOff = read_u32_le(header + 60);
    uint32_t typeIdsSize = read_u32_le(header + 64);
    uint32_t typeIdsOff = read_u32_le(header + 68);
    uint32_t protoIdsSize = read_u32_le(header + 72);
    uint32_t protoIdsOff = read_u32_le(header + 76);
    uint32_t fieldIdsSize = read_u32_le(header + 80);
    uint32_t fieldIdsOff = read_u32_le(header + 84);
    uint32_t methodIdsSize = read_u32_le(header + 88);
    uint32_t methodIdsOff = read_u32_le(header + 92);
    uint32_t classDefsSize = read_u32_le(header + 96);
    uint32_t classDefsOff = read_u32_le(header + 100);
    uint32_t dataSize = read_u32_le(header + 104);
    uint32_t dataOff = read_u32_le(header + 108);

    if (headerSize != 0x70) return false;
    if (endianTag != 0x12345678) return false;
    if (fileSize < 0x70) return false;
    if (maxDumpBytes == 0) maxDumpBytes = 256u * 1024u * 1024u;
    if (fileSize > maxDumpBytes) return false;
    if (address + fileSize < address || address + fileSize > regionEnd) return false;
    if (stringIdsSize == 0 || typeIdsSize == 0 || classDefsSize == 0) return false;

    if (!valid_table_bounds(fileSize, stringIdsSize, stringIdsOff, 4)) return false;
    if (!valid_table_bounds(fileSize, typeIdsSize, typeIdsOff, 4)) return false;
    if (!valid_table_bounds(fileSize, protoIdsSize, protoIdsOff, 12)) return false;
    if (!valid_table_bounds(fileSize, fieldIdsSize, fieldIdsOff, 8)) return false;
    if (!valid_table_bounds(fileSize, methodIdsSize, methodIdsOff, 8)) return false;
    if (!valid_table_bounds(fileSize, classDefsSize, classDefsOff, 32)) return false;
    if (dataSize > 0) {
        uint64_t dataEnd = static_cast<uint64_t>(dataOff) + dataSize;
        if (dataOff == 0 || dataOff >= fileSize || dataEnd > fileSize) return false;
    }
    if (!validate_map_list_at(address, fileSize, mapOff)) return false;
    if (!relaxed) {
        if (!validate_index_tables_at(address, fileSize,
                                      stringIdsSize, stringIdsOff,
                                      typeIdsSize, typeIdsOff,
                                      protoIdsSize, protoIdsOff,
                                      fieldIdsSize, fieldIdsOff,
                                      methodIdsSize, methodIdsOff,
                                      classDefsSize, classDefsOff)) return false;
        if (!validate_string_offsets_sample_at(address, fileSize, stringIdsSize, stringIdsOff)) return false;
    }

    char version[4] = { static_cast<char>(header[4]), static_cast<char>(header[5]), static_cast<char>(header[6]), 0 };
    hit.address = address;
    hit.fileSize = fileSize;
    hit.version = version;
    return true;
}

static std::string make_dump_path(const std::string& outDir, size_t index, const DexHit& hit) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s/memdex_%03zu_%" PRIxPTR "_%u.dex",
             outDir.c_str(), index, hit.address, hit.fileSize);
    return std::string(buf);
}

static bool dump_memory_to_file(uintptr_t address, uint32_t fileSize, const std::string& path) {
    int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (fd < 0) return false;
    const size_t chunkSize = 1024 * 1024;
    std::vector<uint8_t> buffer(chunkSize);
    uint32_t remaining = fileSize;
    uintptr_t cursor = address;
    bool ok = true;
    while (remaining > 0) {
        size_t n = remaining > chunkSize ? chunkSize : remaining;
        if (!safe_copy(buffer.data(), reinterpret_cast<const void*>(cursor), n)) {
            ok = false;
            break;
        }
        ssize_t written = write(fd, buffer.data(), n);
        if (written != static_cast<ssize_t>(n)) {
            ok = false;
            break;
        }
        cursor += n;
        remaining -= static_cast<uint32_t>(n);
    }
    close(fd);
    if (!ok) unlink(path.c_str());
    return ok;
}

static jstring jstr(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

static void map_put(JNIEnv* env, jobject map, const char* key, jobject value) {
    jclass mapCls = env->GetObjectClass(map);
    jmethodID put = env->GetMethodID(mapCls, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jstring k = env->NewStringUTF(key);
    env->CallObjectMethod(map, put, k, value);
    env->DeleteLocalRef(k);
    env->DeleteLocalRef(mapCls);
}

static jobject boxed_long(JNIEnv* env, jlong value) {
    jclass cls = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(J)Ljava/lang/Long;");
    jobject out = env->CallStaticObjectMethod(cls, valueOf, value);
    env->DeleteLocalRef(cls);
    return out;
}

static jobject boxed_int(JNIEnv* env, jint value) {
    jclass cls = env->FindClass("java/lang/Integer");
    jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(I)Ljava/lang/Integer;");
    jobject out = env->CallStaticObjectMethod(cls, valueOf, value);
    env->DeleteLocalRef(cls);
    return out;
}

static jobject boxed_bool(JNIEnv* env, bool value) {
    jclass cls = env->FindClass("java/lang/Boolean");
    jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(Z)Ljava/lang/Boolean;");
    jobject out = env->CallStaticObjectMethod(cls, valueOf, value ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(cls);
    return out;
}

static jobject hit_to_map(JNIEnv* env, const DexHit& hit) {
    jclass linkedHashMapCls = env->FindClass("java/util/LinkedHashMap");
    jmethodID ctor = env->GetMethodID(linkedHashMapCls, "<init>", "()V");
    jobject map = env->NewObject(linkedHashMapCls, ctor);
    map_put(env, map, "path", jstr(env, hit.dumpPath));
    map_put(env, map, "entry", nullptr);
    map_put(env, map, "type", jstr(env, "dex"));
    map_put(env, map, "origin", jstr(env, "memory-scan"));
    map_put(env, map, "address", boxed_long(env, static_cast<jlong>(hit.address)));
    map_put(env, map, "fileSize", boxed_long(env, static_cast<jlong>(hit.fileSize)));
    map_put(env, map, "version", jstr(env, hit.version));
    map_put(env, map, "mapStart", boxed_long(env, static_cast<jlong>(hit.mapStart)));
    map_put(env, map, "mapEnd", boxed_long(env, static_cast<jlong>(hit.mapEnd)));
    map_put(env, map, "mapPerms", jstr(env, hit.mapPerms));
    map_put(env, map, "mapPath", jstr(env, hit.mapPath));
    map_put(env, map, "dumped", boxed_bool(env, true));
    env->DeleteLocalRef(linkedHashMapCls);
    return map;
}


static jobject string_list(JNIEnv* env, const std::vector<std::string>& values) {
    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jmethodID ctor = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID add = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
    jobject list = env->NewObject(arrayListCls, ctor);
    for (const std::string& value : values) {
        jstring s = jstr(env, value);
        env->CallBooleanMethod(list, add, s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(arrayListCls);
    return list;
}

static std::string hex_context(const uint8_t* data, size_t size) {
    static const char* digits = "0123456789abcdef";
    std::string out;
    size_t limit = std::min<size_t>(size, 256);
    out.reserve(limit * 3);
    for (size_t i = 0; i < limit; ++i) {
        if (i) out.push_back(' ');
        uint8_t b = data[i];
        out.push_back(digits[(b >> 4) & 0xf]);
        out.push_back(digits[b & 0xf]);
    }
    return out;
}

static std::string ascii_context(const uint8_t* data, size_t size) {
    std::string out;
    size_t limit = std::min<size_t>(size, 1024);
    out.reserve(limit);
    for (size_t i = 0; i < limit; ++i) {
        uint8_t b = data[i];
        if (b >= 32 && b <= 126) out.push_back(static_cast<char>(b));
        else out.push_back('.');
    }
    return out;
}

static std::string make_window_path(const std::string& outDir, size_t index, uintptr_t address, const std::string& needle) {
    std::string safeNeedle;
    for (char c : needle) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') safeNeedle.push_back(c);
        else safeNeedle.push_back('_');
        if (safeNeedle.size() >= 48) break;
    }
    if (safeNeedle.empty()) safeNeedle = "hit";
    char buf[768];
    snprintf(buf, sizeof(buf), "%s/stringwin_%03zu_%" PRIxPTR "_%s.bin", outDir.c_str(), index, address, safeNeedle.c_str());
    return std::string(buf);
}

static bool dump_window_to_file(uintptr_t center, uintptr_t regionStart, uintptr_t regionEnd, size_t windowBytes, const std::string& path, uintptr_t& dumpedStart, size_t& dumpedSize) {
    if (windowBytes == 0 || path.empty()) return false;
    size_t half = windowBytes / 2;
    uintptr_t start = center > regionStart + half ? center - half : regionStart;
    uintptr_t end = start + windowBytes;
    if (end < start || end > regionEnd) end = regionEnd;
    if (end <= start) return false;
    dumpedStart = start;
    dumpedSize = static_cast<size_t>(end - start);
    return dump_memory_to_file(start, static_cast<uint32_t>(std::min<size_t>(dumpedSize, 0xffffffffu)), path);
}

static jobject string_hit_to_map(JNIEnv* env,
                                 uintptr_t address,
                                 const MapRegion& region,
                                 const std::vector<std::string>& matches,
                                 const std::string& primaryNeedle,
                                 const std::string& contextAscii,
                                 const std::string& contextHex,
                                 const std::string& windowPath,
                                 uintptr_t windowStart,
                                 size_t windowSize) {
    jclass linkedHashMapCls = env->FindClass("java/util/LinkedHashMap");
    jmethodID ctor = env->GetMethodID(linkedHashMapCls, "<init>", "()V");
    jobject map = env->NewObject(linkedHashMapCls, ctor);
    map_put(env, map, "address", boxed_long(env, static_cast<jlong>(address)));
    map_put(env, map, "mapStart", boxed_long(env, static_cast<jlong>(region.start)));
    map_put(env, map, "mapEnd", boxed_long(env, static_cast<jlong>(region.end)));
    map_put(env, map, "mapSize", boxed_long(env, static_cast<jlong>(region.end - region.start)));
    map_put(env, map, "mapPerms", jstr(env, region.perms));
    map_put(env, map, "mapPath", jstr(env, region.pathname));
    map_put(env, map, "needle", jstr(env, primaryNeedle));
    map_put(env, map, "matches", string_list(env, matches));
    map_put(env, map, "matchCount", boxed_int(env, static_cast<jint>(matches.size())));
    map_put(env, map, "contextAscii", jstr(env, contextAscii));
    map_put(env, map, "contextHex", jstr(env, contextHex));
    if (!windowPath.empty()) {
        map_put(env, map, "windowPath", jstr(env, windowPath));
        map_put(env, map, "windowStart", boxed_long(env, static_cast<jlong>(windowStart)));
        map_put(env, map, "windowSize", boxed_long(env, static_cast<jlong>(windowSize)));
    } else {
        map_put(env, map, "windowPath", nullptr);
    }
    env->DeleteLocalRef(linkedHashMapCls);
    return map;
}

static std::vector<size_t> find_needle_offsets(const uint8_t* data, size_t size, const std::string& needle, size_t limit) {
    std::vector<size_t> out;
    if (needle.empty() || needle.size() > size || limit == 0) return out;
    size_t nlen = needle.size();
    for (size_t i = 0; i + nlen <= size; ++i) {
        if (memcmp(data + i, needle.data(), nlen) == 0) {
            out.push_back(i);
            if (out.size() >= limit) break;
            i += nlen > 0 ? nlen - 1 : 0;
        }
    }
    return out;
}

static std::vector<std::string> jstring_array_to_vector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    jsize len = env->GetArrayLength(arr);
    for (jsize i = 0; i < len; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (!s) continue;
        const char* chars = env->GetStringUTFChars(s, nullptr);
        if (chars) {
            out.emplace_back(chars);
            env->ReleaseStringUTFChars(s, chars);
        }
        env->DeleteLocalRef(s);
    }
    return out;
}



static std::string make_raw_dump_path(const std::string& outDir, size_t index, uintptr_t address, uint32_t dumpSize, const char* suffix) {
    char buf[768];
    snprintf(buf, sizeof(buf), "%s/rawdex_%03zu_%" PRIxPTR "_%u_%s.dex",
             outDir.c_str(), index, address, dumpSize, suffix == nullptr ? "candidate" : suffix);
    return std::string(buf);
}

static bool probe_dex_raw_candidate_at(uintptr_t address,
                                       uintptr_t regionEnd,
                                       uint32_t maxDumpBytes,
                                       uint32_t rawWindowBytes,
                                       bool includeInvalid,
                                       DexHit& hit,
                                       uint32_t& headerFileSize,
                                       bool& headerValid,
                                       std::string& reason) {
    headerValid = false;
    headerFileSize = 0;
    uint8_t header[0x70];
    if (address + sizeof(header) > regionEnd) {
        reason = "no-full-header";
        return false;
    }
    if (!safe_copy(header, reinterpret_cast<const void*>(address), sizeof(header))) {
        reason = "header-copy-failed";
        return false;
    }
    if (!(header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n')) {
        reason = "not-dex-magic";
        return false;
    }
    if (!(header[4] == '0' && (header[5] == '3' || header[5] == '4') && header[7] == 0)) {
        reason = "bad-version";
        if (!includeInvalid) return false;
    }
    uint32_t fileSize = read_u32_le(header + 32);
    uint32_t headerSize = read_u32_le(header + 36);
    uint32_t endianTag = read_u32_le(header + 40);
    headerFileSize = fileSize;
    if (maxDumpBytes == 0) maxDumpBytes = 256u * 1024u * 1024u;
    if (rawWindowBytes == 0) rawWindowBytes = 128u * 1024u * 1024u;
    if (rawWindowBytes > maxDumpBytes) rawWindowBytes = maxDumpBytes;

    uint64_t remain = address < regionEnd ? static_cast<uint64_t>(regionEnd - address) : 0;
    if (remain < 0x70) {
        reason = "region-too-small";
        return false;
    }
    bool shape = headerSize == 0x70 && endianTag == 0x12345678 && fileSize >= 0x70 && fileSize <= maxDumpBytes;
    bool complete = shape && static_cast<uint64_t>(fileSize) <= remain;
    uint32_t dumpSize = 0;
    if (complete) {
        dumpSize = fileSize;
        headerValid = true;
        reason = "header-filesize";
    } else if (includeInvalid) {
        uint64_t wanted = fileSize >= 0x70 && fileSize <= maxDumpBytes ? static_cast<uint64_t>(fileSize) : static_cast<uint64_t>(rawWindowBytes);
        if (wanted == 0) wanted = rawWindowBytes;
        dumpSize = static_cast<uint32_t>(std::min<uint64_t>(std::min<uint64_t>(wanted, rawWindowBytes), remain));
        if (dumpSize < 0x70) return false;
        if (!shape) reason = "invalid-header-window";
        else reason = "partial-header-filesize-window";
    } else {
        reason = shape ? "header-size-outside-region" : "invalid-header";
        return false;
    }

    char version[4] = { static_cast<char>(header[4]), static_cast<char>(header[5]), static_cast<char>(header[6]), 0 };
    hit.address = address;
    hit.fileSize = dumpSize;
    hit.version = version;
    return true;
}

static bool required_needles_match(uintptr_t address, uint32_t fileSize, const std::vector<std::string>& needles, bool requireAll) {
    if (needles.empty()) return true;
    const size_t chunkSize = 2 * 1024 * 1024;
    std::vector<uint8_t> chunk(chunkSize + 256);
    std::vector<bool> matched(needles.size(), false);
    uint32_t offset = 0;
    while (offset < fileSize) {
        size_t n = std::min<size_t>(chunkSize, fileSize - offset);
        if (!safe_copy(chunk.data(), reinterpret_cast<const void*>(address + offset), n)) return false;
        mark_required_matches(chunk.data(), n, needles, matched);
        if (requireAll ? all_matched(matched) : any_matched(matched)) return true;
        offset += static_cast<uint32_t>(n);
    }
    return false;
}

static bool safe_read_pointer_abs(uintptr_t address, uintptr_t& out) {
    uintptr_t value = 0;
    if (!safe_copy(&value, reinterpret_cast<const void*>(address), sizeof(value))) return false;
    out = value;
    return true;
}

static bool probe_dex_base_no_region(uintptr_t address,
                                     uint32_t maxDexBytes,
                                     DexHit& hit,
                                     std::string& reason) {
    if (address == 0) {
        reason = "null-address";
        return false;
    }
    uint8_t header[0x70];
    if (!safe_copy(header, reinterpret_cast<const void*>(address), sizeof(header))) {
        reason = "header-copy-failed";
        return false;
    }
    if (!(header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n')) {
        reason = "not-dex-magic";
        return false;
    }
    if (!(header[4] == '0' && (header[5] == '3' || header[5] == '4') && header[7] == 0)) {
        reason = "bad-dex-version";
        return false;
    }
    uint32_t fileSize = read_u32_le(header + 32);
    uint32_t headerSize = read_u32_le(header + 36);
    uint32_t endianTag = read_u32_le(header + 40);
    if (maxDexBytes == 0) maxDexBytes = 256u * 1024u * 1024u;
    if (headerSize != 0x70) {
        reason = "bad-header-size";
        return false;
    }
    if (endianTag != 0x12345678) {
        reason = "bad-endian-tag";
        return false;
    }
    if (fileSize < 0x70 || fileSize > maxDexBytes) {
        reason = "bad-file-size";
        return false;
    }
    uint8_t tail = 0;
    if (!safe_copy(&tail, reinterpret_cast<const void*>(address + fileSize - 1), 1)) {
        reason = "tail-copy-failed";
        return false;
    }
    char version[4] = { static_cast<char>(header[4]), static_cast<char>(header[5]), static_cast<char>(header[6]), 0 };
    hit.address = address;
    hit.fileSize = fileSize;
    hit.version = version;
    reason = "ok";
    return true;
}

struct CookieDexCandidate {
    DexHit hit;
    std::string strategy;
    uintptr_t cookieValue = 0;
    uintptr_t dexFileObject = 0;
    uint32_t beginOffset = 0;
};

static bool add_cookie_candidate(std::vector<CookieDexCandidate>& out,
                                 std::set<uintptr_t>& seenBases,
                                 uintptr_t base,
                                 uint32_t maxDexBytes,
                                 uintptr_t cookieValue,
                                 uintptr_t dexFileObject,
                                 uint32_t beginOffset,
                                 const std::string& strategy,
                                 bool verbose) {
    DexHit hit;
    std::string reason;
    if (!probe_dex_base_no_region(base, maxDexBytes, hit, reason)) {
        if (verbose) ALOGI("cookie candidate miss strategy=%s cookie=%" PRIxPTR " obj=%" PRIxPTR " base=%" PRIxPTR " reason=%s",
                           strategy.c_str(), cookieValue, dexFileObject, base, reason.c_str());
        return false;
    }
    if (seenBases.find(hit.address) != seenBases.end()) return false;
    seenBases.insert(hit.address);
    CookieDexCandidate c;
    c.hit = hit;
    c.strategy = strategy;
    c.cookieValue = cookieValue;
    c.dexFileObject = dexFileObject;
    c.beginOffset = beginOffset;
    out.push_back(c);
    if (verbose) ALOGI("cookie candidate hit strategy=%s cookie=%" PRIxPTR " obj=%" PRIxPTR " base=%" PRIxPTR " size=%u",
                       strategy.c_str(), cookieValue, dexFileObject, hit.address, hit.fileSize);
    return true;
}

static void probe_art_dexfile_object(std::vector<CookieDexCandidate>& out,
                                     std::set<uintptr_t>& seenBases,
                                     uintptr_t cookieValue,
                                     uintptr_t dexFileObject,
                                     uint32_t maxDexBytes,
                                     bool verbose,
                                     const std::string& prefix) {
    if (dexFileObject == 0) return;
    const uint32_t maxProbeBytes = 192;
    const uint32_t step = static_cast<uint32_t>(sizeof(uintptr_t));
    for (uint32_t off = 0; off <= maxProbeBytes; off += step) {
        uintptr_t begin = 0;
        if (!safe_read_pointer_abs(dexFileObject + off, begin)) continue;
        if (begin == 0) continue;
        add_cookie_candidate(out, seenBases, begin, maxDexBytes, cookieValue, dexFileObject, off,
                             prefix + "begin_@" + std::to_string(off), verbose);
    }
}

static std::vector<CookieDexCandidate> collect_cookie_candidates(const std::vector<uintptr_t>& cookieValues,
                                                                  uint32_t maxDexBytes,
                                                                  bool verbose) {
    std::vector<CookieDexCandidate> out;
    std::set<uintptr_t> seenBases;
    for (uintptr_t cookie : cookieValues) {
        if (cookie == 0) continue;
        add_cookie_candidate(out, seenBases, cookie, maxDexBytes, cookie, 0, 0, "cookie-as-dex-base", verbose);
        probe_art_dexfile_object(out, seenBases, cookie, cookie, maxDexBytes, verbose, "cookie-as-art-dexfile:");
        const uint32_t maxCookieArrayProbe = 256;
        const uint32_t step = static_cast<uint32_t>(sizeof(uintptr_t));
        for (uint32_t off = 0; off <= maxCookieArrayProbe; off += step) {
            uintptr_t value = 0;
            if (!safe_read_pointer_abs(cookie + off, value)) continue;
            if (value == 0) continue;
            add_cookie_candidate(out, seenBases, value, maxDexBytes, cookie, 0, off,
                                 "cookie-array-value-as-dex-base@" + std::to_string(off), verbose);
            probe_art_dexfile_object(out, seenBases, cookie, value, maxDexBytes, verbose,
                                     "cookie-array-value-as-art-dexfile@" + std::to_string(off) + ":");
        }
    }
    return out;
}

static std::string make_cookie_dump_path(const std::string& outDir, size_t index, const CookieDexCandidate& c) {
    char buf[768];
    snprintf(buf, sizeof(buf), "%s/cookie_%03zu_%" PRIxPTR "_%u.dex",
             outDir.c_str(), index, c.hit.address, c.hit.fileSize);
    return std::string(buf);
}

static jobject cookie_candidate_to_map(JNIEnv* env, const CookieDexCandidate& c) {
    jobject map = hit_to_map(env, c.hit);
    map_put(env, map, "origin", jstr(env, "dexfile-cookie"));
    map_put(env, map, "strategy", jstr(env, c.strategy));
    map_put(env, map, "cookieValue", boxed_long(env, static_cast<jlong>(c.cookieValue)));
    map_put(env, map, "dexFileObject", boxed_long(env, static_cast<jlong>(c.dexFileObject)));
    map_put(env, map, "beginOffset", boxed_int(env, static_cast<jint>(c.beginOffset)));
    return map;
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeDumpFromCookies(
        JNIEnv* env,
        jclass,
        jstring outputDir,
        jlongArray cookieArray,
        jint maxDexBytes,
        jint maxDumpCount,
        jboolean verbose) {

    const char* outChars = env->GetStringUTFChars(outputDir, nullptr);
    std::string outDir = outChars ? outChars : "";
    if (outChars) env->ReleaseStringUTFChars(outputDir, outChars);

    std::vector<uintptr_t> cookies;
    if (cookieArray != nullptr) {
        jsize len = env->GetArrayLength(cookieArray);
        std::vector<jlong> tmp(static_cast<size_t>(len));
        if (len > 0) env->GetLongArrayRegion(cookieArray, 0, len, tmp.data());
        for (jlong v : tmp) if (v != 0) cookies.push_back(static_cast<uintptr_t>(v));
    }

    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(arrayListCls, arrayListCtor);

    uint32_t maxDex = maxDexBytes <= 0 ? (256u * 1024u * 1024u) : static_cast<uint32_t>(maxDexBytes);
    int maxCount = maxDumpCount <= 0 ? 128 : maxDumpCount;
    install_signal_handlers();
    std::vector<CookieDexCandidate> candidates = collect_cookie_candidates(cookies, maxDex, verbose == JNI_TRUE);
    size_t dumpIndex = 0;
    for (CookieDexCandidate& c : candidates) {
        if (static_cast<int>(dumpIndex) >= maxCount) break;
        c.hit.dumpPath = make_cookie_dump_path(outDir, dumpIndex, c);
        if (!dump_memory_to_file(c.hit.address, c.hit.fileSize, c.hit.dumpPath)) {
            if (verbose == JNI_TRUE) ALOGW("cookie dump failed base=%" PRIxPTR " size=%u path=%s", c.hit.address, c.hit.fileSize, c.hit.dumpPath.c_str());
            continue;
        }
        jobject map = cookie_candidate_to_map(env, c);
        env->CallBooleanMethod(result, arrayListAdd, map);
        env->DeleteLocalRef(map);
        dumpIndex++;
    }
    restore_signal_handlers();
    env->DeleteLocalRef(arrayListCls);
    ALOGI("cookie dump finished cookies=%zu candidates=%zu dumped=%zu out=%s", cookies.size(), candidates.size(), dumpIndex, outDir.c_str());
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeScanAndDump(
        JNIEnv* env,
        jclass,
        jstring outputDir,
        jint maxRegionBytes,
        jint maxDumpBytes,
        jint maxDumpCount,
        jboolean includeAnonymous,
        jboolean includeFileBacked,
        jboolean relaxed,
        jobjectArray requireAsciiContains,
        jboolean requireAllAsciiContains) {

    const char* outChars = env->GetStringUTFChars(outputDir, nullptr);
    std::string outDir = outChars ? outChars : "";
    if (outChars) env->ReleaseStringUTFChars(outputDir, outChars);

    std::vector<std::string> needles = jstring_array_to_vector(env, requireAsciiContains);

    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(arrayListCls, arrayListCtor);

    if (outDir.empty()) return result;

    int maxDumps = maxDumpCount <= 0 ? 32 : maxDumpCount;
    uint64_t maxRegion = maxRegionBytes <= 0 ? (256ull * 1024ull * 1024ull) : static_cast<uint64_t>(maxRegionBytes);
    uint32_t maxDump = maxDumpBytes <= 0 ? (256u * 1024u * 1024u) : static_cast<uint32_t>(maxDumpBytes);

    install_signal_handlers();

    std::vector<MapRegion> maps = read_maps();
    ALOGI("scan start maps=%zu out=%s maxRegion=%" PRIu64 " maxDumpBytes=%u maxDumpCount=%d includeAnon=%d includeFile=%d relaxed=%d needles=%zu requireAll=%d",
          maps.size(), outDir.c_str(), maxRegion, maxDump, maxDumps,
          includeAnonymous == JNI_TRUE, includeFileBacked == JNI_TRUE, relaxed == JNI_TRUE, needles.size(), requireAllAsciiContains == JNI_TRUE);

    std::set<std::string> seen;
    size_t dumpIndex = 0;
    const size_t chunkSize = 1024 * 1024;
    const size_t overlap = 0x100;
    std::vector<uint8_t> chunk(chunkSize + overlap);

    for (const MapRegion& region : maps) {
        if (dumpIndex >= static_cast<size_t>(maxDumps)) break;
        if (!should_scan_region(region, maxRegion, includeAnonymous == JNI_TRUE, includeFileBacked == JNI_TRUE)) continue;
        uintptr_t start = region.start;
        uintptr_t end = region.end;
        uint64_t regionSize = static_cast<uint64_t>(end - start);
        ALOGI("scan region %" PRIxPTR "-%" PRIxPTR " size=%" PRIu64 " perms=%s path=%s",
              start, end, regionSize, region.perms.c_str(), region.pathname.c_str());

        uintptr_t cursor = start;
        while (cursor < end && dumpIndex < static_cast<size_t>(maxDumps)) {
            size_t n = static_cast<size_t>(std::min<uint64_t>(chunkSize + overlap, end - cursor));
            if (!safe_copy(chunk.data(), reinterpret_cast<const void*>(cursor), n)) {
                cursor += chunkSize;
                continue;
            }
            for (size_t i = 0; i + 8 <= n && dumpIndex < static_cast<size_t>(maxDumps); ++i) {
                if (!(chunk[i] == 'd' && chunk[i + 1] == 'e' && chunk[i + 2] == 'x' && chunk[i + 3] == '\n')) continue;
                uintptr_t addr = cursor + i;
                DexHit hit;
                if (!validate_dex_header_at(addr, end, maxDump, relaxed == JNI_TRUE, hit)) continue;
                std::string key = std::to_string(static_cast<unsigned long long>(hit.address)) + ":" + std::to_string(hit.fileSize);
                if (seen.find(key) != seen.end()) continue;
                seen.insert(key);

                if (!required_needles_match(hit.address, hit.fileSize, needles, requireAllAsciiContains == JNI_TRUE)) {
                    ALOGI("dex header filtered by needles addr=%" PRIxPTR " size=%u requireAll=%d", hit.address, hit.fileSize, requireAllAsciiContains == JNI_TRUE);
                    continue;
                }

                hit.mapStart = region.start;
                hit.mapEnd = region.end;
                hit.mapPerms = region.perms;
                hit.mapPath = region.pathname;
                hit.dumpPath = make_dump_path(outDir, dumpIndex, hit);

                if (!dump_memory_to_file(hit.address, hit.fileSize, hit.dumpPath)) {
                    ALOGW("dump failed addr=%" PRIxPTR " size=%u path=%s", hit.address, hit.fileSize, hit.dumpPath.c_str());
                    continue;
                }

                ALOGI("dumped dex addr=%" PRIxPTR " size=%u version=%s path=%s map=%s",
                      hit.address, hit.fileSize, hit.version.c_str(), hit.dumpPath.c_str(), hit.mapPath.c_str());
                jobject map = hit_to_map(env, hit);
                env->CallBooleanMethod(result, arrayListAdd, map);
                env->DeleteLocalRef(map);
                dumpIndex++;

                if (hit.fileSize > 8) {
                    // Skip inside the same dex body to reduce duplicate scans.
                    i += std::min<size_t>(hit.fileSize, n - i) - 1;
                }
            }
            if (end - cursor <= chunkSize) break;
            cursor += chunkSize;
        }
    }

    restore_signal_handlers();
    env->DeleteLocalRef(arrayListCls);
    ALOGI("scan finished dumps=%zu", dumpIndex);
    return result;
}


extern "C" JNIEXPORT jobject JNICALL
Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeScanAndDumpRaw(
        JNIEnv* env,
        jclass,
        jstring outputDir,
        jint maxRegionBytes,
        jint maxDumpBytes,
        jint maxDumpCount,
        jboolean includeAnonymous,
        jboolean includeFileBacked,
        jboolean includeInvalid,
        jobjectArray requireAsciiContains,
        jboolean requireAllAsciiContains,
        jint rawWindowBytes) {

    const char* outChars = env->GetStringUTFChars(outputDir, nullptr);
    std::string outDir = outChars ? outChars : "";
    if (outChars) env->ReleaseStringUTFChars(outputDir, outChars);

    std::vector<std::string> needles = jstring_array_to_vector(env, requireAsciiContains);

    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(arrayListCls, arrayListCtor);

    if (outDir.empty()) return result;

    int maxDumps = maxDumpCount <= 0 ? 64 : maxDumpCount;
    uint64_t maxRegion = maxRegionBytes <= 0 ? (256ull * 1024ull * 1024ull) : static_cast<uint64_t>(maxRegionBytes);
    uint32_t maxDump = maxDumpBytes <= 0 ? (256u * 1024u * 1024u) : static_cast<uint32_t>(maxDumpBytes);
    uint32_t rawWin = rawWindowBytes <= 0 ? (128u * 1024u * 1024u) : static_cast<uint32_t>(rawWindowBytes);
    if (rawWin > maxDump) rawWin = maxDump;

    install_signal_handlers();

    std::vector<MapRegion> maps = read_maps();
    ALOGI("raw scan start maps=%zu out=%s maxRegion=%" PRIu64 " maxDumpBytes=%u maxDumpCount=%d rawWindow=%u includeAnon=%d includeFile=%d includeInvalid=%d needles=%zu requireAll=%d",
          maps.size(), outDir.c_str(), maxRegion, maxDump, maxDumps, rawWin,
          includeAnonymous == JNI_TRUE, includeFileBacked == JNI_TRUE, includeInvalid == JNI_TRUE,
          needles.size(), requireAllAsciiContains == JNI_TRUE);

    std::set<std::string> seen;
    size_t dumpIndex = 0;
    const size_t chunkSize = 1024 * 1024;
    const size_t overlap = 0x200;
    std::vector<uint8_t> chunk(chunkSize + overlap);

    for (const MapRegion& region : maps) {
        if (dumpIndex >= static_cast<size_t>(maxDumps)) break;
        if (!should_scan_region(region, maxRegion, includeAnonymous == JNI_TRUE, includeFileBacked == JNI_TRUE)) continue;
        uintptr_t start = region.start;
        uintptr_t end = region.end;
        uint64_t regionSize = static_cast<uint64_t>(end - start);
        ALOGI("raw scan region %" PRIxPTR "-%" PRIxPTR " size=%" PRIu64 " perms=%s path=%s",
              start, end, regionSize, region.perms.c_str(), region.pathname.c_str());

        uintptr_t cursor = start;
        while (cursor < end && dumpIndex < static_cast<size_t>(maxDumps)) {
            size_t n = static_cast<size_t>(std::min<uint64_t>(chunkSize + overlap, end - cursor));
            if (!safe_copy(chunk.data(), reinterpret_cast<const void*>(cursor), n)) {
                cursor += chunkSize;
                continue;
            }
            for (size_t i = 0; i + 8 <= n && dumpIndex < static_cast<size_t>(maxDumps); ++i) {
                if (!(chunk[i] == 'd' && chunk[i + 1] == 'e' && chunk[i + 2] == 'x' && chunk[i + 3] == '\n')) continue;
                uintptr_t addr = cursor + i;
                DexHit hit;
                uint32_t headerFileSize = 0;
                bool headerValid = false;
                std::string reason;
                if (!probe_dex_raw_candidate_at(addr, end, maxDump, rawWin, includeInvalid == JNI_TRUE, hit, headerFileSize, headerValid, reason)) continue;

                std::string key = std::to_string(static_cast<unsigned long long>(addr)) + ":" + std::to_string(hit.fileSize);
                if (seen.find(key) != seen.end()) continue;
                seen.insert(key);

                if (!required_needles_match(hit.address, hit.fileSize, needles, requireAllAsciiContains == JNI_TRUE)) {
                    ALOGI("raw candidate filtered by needles addr=%" PRIxPTR " dumpSize=%u reason=%s requireAll=%d",
                          hit.address, hit.fileSize, reason.c_str(), requireAllAsciiContains == JNI_TRUE);
                    continue;
                }

                hit.mapStart = region.start;
                hit.mapEnd = region.end;
                hit.mapPerms = region.perms;
                hit.mapPath = region.pathname;
                hit.dumpPath = make_raw_dump_path(outDir, dumpIndex, hit.address, hit.fileSize, headerValid ? "header" : "raw");

                if (!dump_memory_to_file(hit.address, hit.fileSize, hit.dumpPath)) {
                    ALOGW("raw dump failed addr=%" PRIxPTR " size=%u path=%s reason=%s", hit.address, hit.fileSize, hit.dumpPath.c_str(), reason.c_str());
                    continue;
                }

                ALOGI("raw dumped dex-like addr=%" PRIxPTR " dumpSize=%u headerFileSize=%u version=%s reason=%s path=%s map=%s",
                      hit.address, hit.fileSize, headerFileSize, hit.version.c_str(), reason.c_str(), hit.dumpPath.c_str(), hit.mapPath.c_str());
                jobject map = hit_to_map(env, hit);
                map_put(env, map, "origin", jstr(env, "memory-raw-salvage"));
                map_put(env, map, "rawSalvage", boxed_bool(env, true));
                map_put(env, map, "headerValid", boxed_bool(env, headerValid));
                map_put(env, map, "headerFileSize", boxed_long(env, static_cast<jlong>(headerFileSize)));
                map_put(env, map, "rawReason", jstr(env, reason));
                env->CallBooleanMethod(result, arrayListAdd, map);
                env->DeleteLocalRef(map);
                dumpIndex++;

                // In raw mode keep scanning nearby dex magic, but jump over the magic itself.
                i += 7;
            }
            if (end - cursor <= chunkSize) break;
            cursor += chunkSize;
        }
    }

    restore_signal_handlers();
    env->DeleteLocalRef(arrayListCls);
    ALOGI("raw scan finished dumps=%zu", dumpIndex);
    return result;
}


extern "C" JNIEXPORT jobject JNICALL
Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeScanStrings(
        JNIEnv* env,
        jclass,
        jstring outputDir,
        jint maxRegionBytes,
        jint maxHits,
        jint contextBytes,
        jint windowBytes,
        jboolean includeAnonymous,
        jboolean includeFileBacked,
        jboolean requireAll,
        jboolean dumpWindows,
        jobjectArray needlesArray) {

    const char* outChars = env->GetStringUTFChars(outputDir, nullptr);
    std::string outDir = outChars ? outChars : "";
    if (outChars) env->ReleaseStringUTFChars(outputDir, outChars);

    std::vector<std::string> needles = jstring_array_to_vector(env, needlesArray);

    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(arrayListCls, arrayListCtor);

    if (needles.empty()) {
        env->DeleteLocalRef(arrayListCls);
        return result;
    }

    int maxHitCount = maxHits <= 0 ? 64 : maxHits;
    uint64_t maxRegion = maxRegionBytes <= 0 ? (256ull * 1024ull * 1024ull) : static_cast<uint64_t>(maxRegionBytes);
    int ctx = contextBytes < 0 ? 0 : contextBytes;
    if (ctx > 4096) ctx = 4096;
    int win = windowBytes < 0 ? 0 : windowBytes;
    if (win > 16 * 1024 * 1024) win = 16 * 1024 * 1024;

    install_signal_handlers();

    std::vector<MapRegion> maps = read_maps();
    ALOGI("string scan start maps=%zu out=%s needles=%zu requireAll=%d maxHits=%d maxRegion=%" PRIu64 " dumpWindows=%d",
          maps.size(), outDir.c_str(), needles.size(), requireAll == JNI_TRUE, maxHitCount, maxRegion, dumpWindows == JNI_TRUE);

    const size_t chunkSize = 1024 * 1024;
    size_t maxNeedleLen = 1;
    for (const std::string& n : needles) maxNeedleLen = std::max(maxNeedleLen, n.size());
    size_t overlap = std::min<size_t>(4096, std::max<size_t>(256, maxNeedleLen + 64));
    std::vector<uint8_t> chunk(chunkSize + overlap);
    int hitCount = 0;
    size_t windowIndex = 0;

    for (const MapRegion& region : maps) {
        if (hitCount >= maxHitCount) break;
        if (!should_scan_region(region, maxRegion, includeAnonymous == JNI_TRUE, includeFileBacked == JNI_TRUE)) continue;
        uintptr_t start = region.start;
        uintptr_t end = region.end;
        uint64_t regionSize = static_cast<uint64_t>(end - start);
        ALOGI("string scan region %" PRIxPTR "-%" PRIxPTR " size=%" PRIu64 " perms=%s path=%s",
              start, end, regionSize, region.perms.c_str(), region.pathname.c_str());

        std::vector<bool> regionMatched(needles.size(), false);
        uintptr_t firstAddress = 0;
        std::string firstNeedle;
        std::vector<uint8_t> firstContext;

        uintptr_t cursor = start;
        while (cursor < end && hitCount < maxHitCount) {
            size_t n = static_cast<size_t>(std::min<uint64_t>(chunkSize + overlap, end - cursor));
            if (!safe_copy(chunk.data(), reinterpret_cast<const void*>(cursor), n)) {
                cursor += chunkSize;
                continue;
            }

            if (requireAll == JNI_TRUE) {
                for (size_t idx = 0; idx < needles.size(); ++idx) {
                    if (regionMatched[idx]) continue;
                    std::vector<size_t> offsets = find_needle_offsets(chunk.data(), n, needles[idx], 1);
                    if (!offsets.empty()) {
                        regionMatched[idx] = true;
                        if (firstAddress == 0) {
                            firstAddress = cursor + offsets[0];
                            firstNeedle = needles[idx];
                            size_t ctxStart = offsets[0] > static_cast<size_t>(ctx) ? offsets[0] - static_cast<size_t>(ctx) : 0;
                            size_t ctxEnd = std::min<size_t>(n, offsets[0] + needles[idx].size() + static_cast<size_t>(ctx));
                            firstContext.assign(chunk.begin() + ctxStart, chunk.begin() + ctxEnd);
                        }
                    }
                }
                if (all_matched(regionMatched)) {
                    std::vector<std::string> matches;
                    for (size_t i = 0; i < needles.size(); ++i) if (regionMatched[i]) matches.push_back(needles[i]);
                    uintptr_t windowStart = 0;
                    size_t windowSize = 0;
                    std::string windowPath;
                    if (dumpWindows == JNI_TRUE && win > 0 && !outDir.empty()) {
                        windowPath = make_window_path(outDir, windowIndex++, firstAddress, firstNeedle);
                        if (!dump_window_to_file(firstAddress, region.start, region.end, static_cast<size_t>(win), windowPath, windowStart, windowSize)) {
                            windowPath.clear();
                        }
                    }
                    std::string ascii = firstContext.empty() ? std::string() : ascii_context(firstContext.data(), firstContext.size());
                    std::string hex = firstContext.empty() ? std::string() : hex_context(firstContext.data(), firstContext.size());
                    jobject map = string_hit_to_map(env, firstAddress, region, matches, firstNeedle, ascii, hex, windowPath, windowStart, windowSize);
                    env->CallBooleanMethod(result, arrayListAdd, map);
                    env->DeleteLocalRef(map);
                    ++hitCount;
                    break;
                }
            } else {
                for (const std::string& needle : needles) {
                    if (hitCount >= maxHitCount) break;
                    std::vector<size_t> offsets = find_needle_offsets(chunk.data(), n, needle, 4);
                    for (size_t off : offsets) {
                        if (hitCount >= maxHitCount) break;
                        uintptr_t addr = cursor + off;
                        size_t ctxStart = off > static_cast<size_t>(ctx) ? off - static_cast<size_t>(ctx) : 0;
                        size_t ctxEnd = std::min<size_t>(n, off + needle.size() + static_cast<size_t>(ctx));
                        std::vector<uint8_t> ctxBuf(chunk.begin() + ctxStart, chunk.begin() + ctxEnd);
                        std::vector<std::string> matches{needle};
                        uintptr_t windowStart = 0;
                        size_t windowSize = 0;
                        std::string windowPath;
                        if (dumpWindows == JNI_TRUE && win > 0 && !outDir.empty()) {
                            windowPath = make_window_path(outDir, windowIndex++, addr, needle);
                            if (!dump_window_to_file(addr, region.start, region.end, static_cast<size_t>(win), windowPath, windowStart, windowSize)) {
                                windowPath.clear();
                            }
                        }
                        std::string ascii = ascii_context(ctxBuf.data(), ctxBuf.size());
                        std::string hex = hex_context(ctxBuf.data(), ctxBuf.size());
                        jobject map = string_hit_to_map(env, addr, region, matches, needle, ascii, hex, windowPath, windowStart, windowSize);
                        env->CallBooleanMethod(result, arrayListAdd, map);
                        env->DeleteLocalRef(map);
                        ++hitCount;
                    }
                }
            }
            if (end - cursor <= chunkSize) break;
            cursor += chunkSize;
        }
    }

    restore_signal_handlers();
    env->DeleteLocalRef(arrayListCls);
    ALOGI("string scan finished hits=%d", hitCount);
    return result;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("top/lovepikachu/XiaoHeiHook/dex/DexMemoryScanner");
    if (cls == nullptr) {
        return JNI_VERSION_1_6;
    }
    JNINativeMethod methods[] = {
            {const_cast<char*>("nativeDumpFromCookies"),
             const_cast<char*>("(Ljava/lang/String;[JIIZ)Ljava/util/ArrayList;"),
             reinterpret_cast<void*>(Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeDumpFromCookies)},
            {const_cast<char*>("nativeScanAndDump"),
             const_cast<char*>("(Ljava/lang/String;IIIZZZ[Ljava/lang/String;Z)Ljava/util/ArrayList;"),
             reinterpret_cast<void*>(Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeScanAndDump)},
            {const_cast<char*>("nativeScanAndDumpRaw"),
             const_cast<char*>("(Ljava/lang/String;IIIZZZ[Ljava/lang/String;ZI)Ljava/util/ArrayList;"),
             reinterpret_cast<void*>(Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeScanAndDumpRaw)},
            {const_cast<char*>("nativeScanStrings"),
             const_cast<char*>("(Ljava/lang/String;IIIIZZZZ[Ljava/lang/String;)Ljava/util/ArrayList;"),
             reinterpret_cast<void*>(Java_top_lovepikachu_XiaoHeiHook_dex_DexMemoryScanner_nativeScanStrings)},
    };
    if (env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        env->DeleteLocalRef(cls);
        return JNI_ERR;
    }
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}
