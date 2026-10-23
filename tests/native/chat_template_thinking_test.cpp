#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>
#include "chat.h"

static std::string render(const common_chat_templates * tmpls, const std::vector<common_chat_msg>& msg, bool gen, bool thinking) {
    common_chat_templates_inputs inputs;
    inputs.messages = msg;
    inputs.add_generation_prompt = gen;
    inputs.use_jinja = true;
    inputs.enable_thinking = thinking;
    return common_chat_templates_apply(tmpls, inputs).prompt.str();
}

int main(int argc, char** argv) {
    if (argc < 2) { std::cerr << "usage: test template.jinja\n"; return 2; }
    std::ifstream f(argv[1]);
    const std::string template_text((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    auto tmpls = common_chat_templates_init(nullptr, template_text);
    std::vector<common_chat_msg> history;
    common_chat_msg system; system.role = "system"; system.content = "Du bist Brumo, ein frecher Stoffbaer. Antworte auf Deutsch.";
    common_chat_msg user; user.role = "user"; user.content = "hi brumo wie gehts???";
    common_chat_msg assistant; assistant.role = "assistant"; assistant.content = "Servus, mir geht's bärig!";
    history.push_back(system);
    const std::string system_plain = render(tmpls.get(), history, false, false);
    const std::string system_think = render(tmpls.get(), history, false, true);
    history.push_back(user);
    const std::string without = render(tmpls.get(), history, true, false);
    const std::string with = render(tmpls.get(), history, true, true);
    const std::string incremental = without.substr(system_plain.size());
    if (without.find("thinking_effort=high") != std::string::npos) throw std::runtime_error("thinking leaked in non-thinking prompt");
    if (without.find("thinking_effort=low") != std::string::npos) throw std::runtime_error("thinking leaked in non-thinking prompt");
    if (without.find("<|Bot|></think>") == std::string::npos) throw std::runtime_error("nonthinking assistant prefill is missing");
    if (with.find("<|Bot|><think>") == std::string::npos) throw std::runtime_error("thinking mode assistant prefill is missing");
    if (incremental.find("hi brumo wie gehts???") == std::string::npos) throw std::runtime_error("incremental user text missing");
    if (incremental.find("<|Bot|></think>") == std::string::npos) throw std::runtime_error("incremental nonthinking prefix missing");
    if (without.size() <= system_plain.size()) throw std::runtime_error("unexpected diff lengths");
    history.push_back(assistant);
    const std::string past_multi = render(tmpls.get(), history, false, false);
    history.push_back(user);
    auto full_again = render(tmpls.get(), history, true, false);
    if (full_again.compare(0, past_multi.size(), past_multi) != 0) throw std::runtime_error("second turn incremental prefix mismatch");
    if (full_again.find("Servus, mir geht's bärig!") == std::string::npos) throw std::runtime_error("assistant history missing");
    if (full_again.substr(past_multi.size()).find("<|Bot|></think>") == std::string::npos) throw std::runtime_error("second turn lacks nonthinking prefix");
    std::cout << "PASS: enable_thinking=false removes high-effort reasoning instructions.\n";
    std::cout << "PASS: direct nonthinking assistant generation prefix: <|Bot|></think>.\n";
    std::cout << "PASS: enable_thinking=true retains <|Bot|><think> as opt-in.\n";
    std::cout << "PASS: incremental rendering and multi-turn chat.\n";
    std::cout << "SHORT_NONTHINK_PREFIX: " << incremental.substr(0, 185) << "\n";
}
