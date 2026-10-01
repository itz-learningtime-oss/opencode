#include <string>

extern "C" const char *opencodeTerminalStlAnchor() {
    static const std::string name = "opencode-terminal";
    return name.c_str();
}
