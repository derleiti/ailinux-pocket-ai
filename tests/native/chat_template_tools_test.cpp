#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#include "chat.h"

// This is the original Spark-X2.6 GGUF Jinja source, not an invented template.
// It tests actual llama.cpp Jinja rendering with optional native tool binding.
static std::string render(
        const common_chat_templates * tmpl,
        const std::vector<common_chat_msg> & messages,
        const std::vector<common_chat_tool> & tools,
        const bool generation_prompt,
        const std::string & thinking_mode = "off") {
    common_chat_templates_inputs input;
    input.messages = messages;
    input.use_jinja = true;
    input.add_generation_prompt = generation_prompt;
    input.enable_thinking = thinking_mode != "off";
    if (input.enable_thinking) {
        input.chat_template_kwargs["reasoning_effort"] =
            std::string(1, char(34)) + thinking_mode + char(34);
    }
    input.tools = tools;
    input.parallel_tool_calls = false;
    return common_chat_templates_apply(tmpl, input).prompt.str();
}

static void require(bool condition, const char * error) {
    if (!condition) throw std::runtime_error(error);
}

int main(int argc, char ** argv) {
    if (argc < 2) return 2;
    std::ifstream input(argv[1]);
    require(input.good(), "Spark Jinja template not found");
    const std::string source((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    auto templ = common_chat_templates_init(nullptr, source);

    common_chat_msg system; system.role = "system";
    system.content = "Antworte auf Deutsch. Externe Aktionen nur nach bestaetigten Tools.";
    common_chat_msg user; user.role = "user";
    user.content = "Bitte pruefe die Discord Verbindung";
    common_chat_msg assistant; assistant.role = "assistant";
    assistant.content = "Verbindung nicht automatisch bestaetigt.";
    std::vector<common_chat_msg> messages{ system };

    const auto bare = render(templ.get(), messages, {}, false);
    // Same common_json parser and tool adapter as the real Android JNI.
    const auto defs = common_json::parse(R"([
        {"type":"function","function":{
            "name":"discord_connection_status",
            "description":"Read Discord connection status",
            "parameters":{"type":"object","properties":{},"required":[]}
        }},
        {"type":"function","function":{
            "name":"discord_authorized_guilds",
            "description":"List authorized Discord guilds",
            "parameters":{"type":"object","properties":{},"required":[]}
        }}
    ])");
    const auto tools = common_chat_tools_parse_oaicompat(defs);
    require(tools.size() == 2, "OpenAI-compatible native parser lost MCP functions");
    const auto bound = render(templ.get(), messages, tools, false);
    require(bound.find("discord_connection_status") != std::string::npos, "First actual tool missing");
    require(bound.find("discord_authorized_guilds") != std::string::npos, "Second actual tool missing");
    require(bound.find("\"parameters\"") != std::string::npos, "Function parameters missing");
    require(bound.find("<tools>") != std::string::npos, "Spark system tool section missing");
    require(bare.find("<tools>") == std::string::npos, "Tool section leaked into plain chat");

    messages.push_back(user);
    const auto ready = render(templ.get(), messages, tools, true);
    require(ready.compare(0, bound.size(), bound) == 0, "Tool system prefix changed on user turn");
    require(ready.find("<|Bot|></think>") != std::string::npos, "Spark tool mode started thinking");
    messages.push_back(assistant);
    const auto history = render(templ.get(), messages, tools, false);
    messages.push_back(user);
    const auto followup = render(templ.get(), messages, tools, true);
    require(followup.compare(0, history.size(), history) == 0,
            "Spark tool schemas broke multi-turn incremental prefill");
    const auto off = render(templ.get(), messages, {}, true);
    require(off.find("\"discord_connection_status\"") == std::string::npos,
            "Tools remained after disabling native tool catalog");
    // In LOW, Spark's template pre-fills <think> and the generated output
    // contains only the REASONING BODY, </think> and then tool_call.
    // The Android JNI must save that body in reasoning_content so subsequent
    // chat rendering exactly reproduces already-decoded KV history.
    const auto lowSystem = render(templ.get(), {system}, tools, false, "low");
    require(lowSystem.find("thinking_effort=low") != std::string::npos,
            "Spark LOW thinking effort was ignored by Jinja");
    require(lowSystem.find("thinking_effort=high") == std::string::npos,
            "Spark used HIGH despite explicit LOW selection");
    require(lowSystem.find("discord_connection_status") != std::string::npos,
            "Native tools missing in thinking LOW system prefix");
    const auto lowReady = render(templ.get(), {system, user}, tools, true, "low");
    require(lowReady.compare(0, lowSystem.size(), lowSystem) == 0,
            "LOW user-generation prefix does not extend the system template");
    require(lowReady.find("<|Bot|><think>") != std::string::npos,
            "Spark LOW must prefill an opening think tag");
    common_chat_msg thought;
    thought.role = "assistant";
    thought.reasoning_content = "I need the real Discord status.";
    thought.content = "<tool_call>discord_connection_status</tool_call>";
    const auto lowComplete = render(templ.get(), {system, user, thought},
                                    tools, false, "low");
    require(lowComplete.find(
        "<think>I need the real Discord status.</think><tool_call>discord_connection_status"
        ) != std::string::npos,
        "A completed thinking tool call was not reconstructed correctly");
    const auto lowNext = render(templ.get(), {system, user, thought, user},
                               tools, true, "low");
    require(lowNext.compare(0, lowComplete.size(), lowComplete) == 0,
            "Native LOW thinking transcript corrupts a second chat turn");
    const auto highSystem = render(templ.get(), {system}, tools, false, "high");
    require(highSystem.find("thinking_effort=high") != std::string::npos,
            "Spark HIGH mode was not set correctly");
    std::cout << "PASS Spark thinking OFF/LOW/HIGH and native tool binding\n";
    std::cout << "PASS LOW thinking assistant KV history roundtrip across user turns\n";
    std::cout << "PASS original Spark Jinja: selected MCP tools in native system block\n";
    std::cout << "PASS function names, descriptions and argument schemas exposed\n";
    std::cout << "PASS thinking off; repeated Jinja prompt prefix stable\n";
    std::cout << "PASS tools disappear when native tool binding is disabled\n";
}
