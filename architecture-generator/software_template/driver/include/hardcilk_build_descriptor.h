#pragma once

#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

struct HardCilkManagementServer
{
    std::string kind;
    std::string task;
    uint64_t index = 0;
    uint64_t baseAddress = 0;
    std::string registerLayout;
    uint64_t queueCapacity = 0;
    uint64_t entryBytes = 0;
    int hbmPort = -1;
    bool rootSeed = false;
};

struct HardCilkManagementBankOverride
{
    std::string region;
    int hbmPort = -1;
};

struct HardCilkBuildDescriptor
{
    bool loaded = false;
    uint64_t schemaVersion = 0;
    std::string architecture = "updated";
    std::string argumentServer = "cached";
    std::string ramaMode = "descriptor-only";
    bool globalStartAvailable = true;
    bool globalStartEnabled = false;
    uint64_t globalStartAddress = 0;
    std::vector<HardCilkManagementServer> servers;

    std::vector<HardCilkManagementServer>
    serversFor(const std::string &task, const std::string &kind) const
    {
        std::vector<HardCilkManagementServer> result;
        for (const auto &server : servers)
            if (server.task == task && server.kind == kind)
                result.push_back(server);
        return result;
    }

    std::vector<HardCilkManagementBankOverride>
    managementBankOverrides() const
    {
        std::vector<HardCilkManagementBankOverride> result;
        if (!loaded || ramaMode == "striped")
            return result;
        for (const auto &server : servers)
        {
            if (server.hbmPort < 0)
                continue;
            std::string prefix;
            if (server.kind == "scheduler") prefix = "sched:";
            else if (server.kind == "spawner") prefix = "spawner:";
            else if (server.kind == "allocator") prefix = "alloc:";
            else if (server.kind == "memoryAllocator") prefix = "memalloc:";
            else continue;
            result.push_back({prefix + server.task + ":" +
                                  std::to_string(server.index),
                              server.hbmPort});
        }
        return result;
    }

    static HardCilkBuildDescriptor loadFromEnvironment()
    {
        HardCilkBuildDescriptor result;
        const char *path = std::getenv("HARDCILK_HBM_DESCRIPTOR");
        if (path == nullptr || path[0] == '\0')
            return result;

        std::ifstream input(path);
        if (!input)
            return result;
        std::ostringstream buffer;
        buffer << input.rdbuf();
        const std::string json = buffer.str();

        auto stringField = [](const std::string &object, const char *key,
                              std::string &value) {
            std::regex pattern(std::string("\\\"") + key +
                               "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
            std::smatch match;
            if (!std::regex_search(object, match, pattern)) return false;
            value = match[1].str();
            return true;
        };
        auto uintField = [](const std::string &object, const char *key,
                            uint64_t &value) {
            std::regex pattern(std::string("\\\"") + key +
                               "\\\"\\s*:\\s*([0-9]+)");
            std::smatch match;
            if (!std::regex_search(object, match, pattern)) return false;
            value = std::stoull(match[1].str());
            return true;
        };
        auto boolField = [](const std::string &object, const char *key,
                            bool &value) {
            std::regex pattern(std::string("\\\"") + key +
                               "\\\"\\s*:\\s*(true|false)");
            std::smatch match;
            if (!std::regex_search(object, match, pattern)) return false;
            value = match[1].str() == "true";
            return true;
        };

        uintField(json, "schemaVersion", result.schemaVersion);
        if (result.schemaVersion < 2)
            return result;
        stringField(json, "architecture", result.architecture);
        stringField(json, "argumentServer", result.argumentServer);
        stringField(json, "ramaMode", result.ramaMode);

        std::regex globalStartPattern(
            "\\\"globalStart\\\"\\s*:\\s*\\{([^}]*)\\}");
        std::smatch globalStartMatch;
        if (std::regex_search(json, globalStartMatch, globalStartPattern))
        {
            const std::string object = globalStartMatch[1].str();
            boolField(object, "available", result.globalStartAvailable);
            boolField(object, "enabled", result.globalStartEnabled);
            uintField(object, "registerAddress", result.globalStartAddress);
        }

        const std::string marker = "\"servers\"";
        const size_t markerPos = json.find(marker);
        const size_t arrayBegin = markerPos == std::string::npos
                                      ? std::string::npos
                                      : json.find('[', markerPos + marker.size());
        if (arrayBegin != std::string::npos)
        {
            int arrayDepth = 1;
            size_t cursor = arrayBegin + 1;
            while (cursor < json.size() && arrayDepth > 0)
            {
                const size_t objectBegin = json.find('{', cursor);
                const size_t arrayEnd = json.find(']', cursor);
                if (arrayEnd == std::string::npos ||
                    (objectBegin != std::string::npos && objectBegin < arrayEnd))
                {
                    int objectDepth = 1;
                    size_t end = objectBegin + 1;
                    while (end < json.size() && objectDepth > 0)
                    {
                        if (json[end] == '{') ++objectDepth;
                        else if (json[end] == '}') --objectDepth;
                        ++end;
                    }
                    if (objectDepth != 0) break;
                    const std::string object =
                        json.substr(objectBegin, end - objectBegin);
                    HardCilkManagementServer server;
                    if (stringField(object, "kind", server.kind) &&
                        stringField(object, "task", server.task) &&
                        uintField(object, "baseAddress", server.baseAddress))
                    {
                        uintField(object, "index", server.index);
                        stringField(object, "registerLayout", server.registerLayout);
                        uintField(object, "queueCapacity", server.queueCapacity);
                        uintField(object, "entryBytes", server.entryBytes);
                        boolField(object, "rootSeed", server.rootSeed);
                        uint64_t port = 0;
                        if (uintField(object, "hbmPort", port))
                            server.hbmPort = static_cast<int>(port);
                        result.servers.push_back(server);
                    }
                    cursor = end;
                }
                else
                {
                    --arrayDepth;
                    cursor = arrayEnd + 1;
                }
            }
        }

        result.loaded = true;
        return result;
    }
};
