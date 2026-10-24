#pragma once

#include <cctype>
#include <string>
#include <string_view>

namespace pocket_native_tool {

/**
 * A generated assistant tool call is the ONLY permissible predecessor of
 * a tool-role response. It must match the exact selected function name,
 * contain a single complete <tool_call> and no other assistant prose.
 *
 * This pure predicate is used by Android JNI and the host regression test.
 */
inline bool matches_pending_call(const std::string &content, const std::string &tool) {
    if (tool.empty() || tool.size() > 128) return false;
    const std::string prefix = "<tool_call>" + tool;
    const auto open = content.find("<tool_call>");
    const auto close = content.find("</tool_call>");
    if (open == std::string::npos || close == std::string::npos ||
            content.find("<tool_call>", open + 1) != std::string::npos ||
            content.find("</tool_call>", close + 1) != std::string::npos) {
        return false;
    }
    if (content.compare(open, prefix.size(), prefix) != 0) return false;
    const size_t after_name = open + prefix.size();
    // Prevent prefix-smuggling e.g. "status" vs "status_evil".
    if (content.compare(after_name, 9, "<arg_key>") != 0 &&
            content.compare(after_name, 12, "</tool_call>") != 0) {
        return false;
    }
    if (close < after_name) return false;
    for (size_t i = 0; i < open; i++) {
        if (!std::isspace(static_cast<unsigned char>(content[i]))) return false;
    }
    for (size_t i = close + std::string("</tool_call>").size(); i < content.size(); i++) {
        if (!std::isspace(static_cast<unsigned char>(content[i]))) return false;
    }
    return true;
}

inline bool is_safe_payload(std::string_view text, const size_t max_length = 2400) {
    return !text.empty() && text.size() <= max_length &&
        text.find('<') == std::string_view::npos;
}

} // namespace pocket_native_tool
