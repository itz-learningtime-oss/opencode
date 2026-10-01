Native libraries placed here are unpacked by Android into nativeLibraryDir,
which is executable. This satisfies Android 10+ W^X for native code.

Required:
  libnode.so      nodejs-mobile Android core (SHARED LIBRARY, not an executable).
                  Fetch with: ./scripts/fetch-nodejs-mobile.sh
                  The app starts Node in-process via node::Start(int, char**)
                  using a PTY that is dup2'd onto fd 0/1/2.

Optional (only if you have a standalone armeabi-v7a executable):
  libnodeexec.so  a real `node` executable (legacy fork/exec path)
  libgit.so       standalone git executable
  librg.so        standalone ripgrep executable

libc++_shared.so is produced by the NDK build (CMake links c++_shared) and is
required by libnode.so.
