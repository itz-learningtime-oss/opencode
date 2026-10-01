#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <termios.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <stdio.h>
#include <dlfcn.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "NativePty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define MAX_ENV 64
#define READ_BUF 4096

typedef struct {
    int master_fd;
    pid_t child_pid;
    pthread_t reader_thread;
    JavaVM *jvm;
    jobject callback;
    volatile int running;
    int cols;
    int rows;
    int inprocess;
    int saved_stdin;
    int saved_stdout;
    int saved_stderr;
} PtySession;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static void set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
}

static void close_fd(int *fd) {
    if (fd && *fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static char **copy_string_array(JNIEnv *env, jobjectArray array, int *out_count) {
    if (array == NULL) {
        *out_count = 0;
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, array);
    if (len <= 0) {
        *out_count = 0;
        return NULL;
    }
    char **out = (char **) calloc((size_t) len + 1, sizeof(char *));
    if (!out) {
        *out_count = 0;
        return NULL;
    }
    int count = 0;
    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        if (js == NULL) {
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        if (utf) {
            out[count] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, js, utf);
            if (out[count]) {
                count++;
            }
        }
        (*env)->DeleteLocalRef(env, js);
    }
    out[count] = NULL;
    *out_count = count;
    return out;
}

static void free_string_array(char **arr) {
    if (!arr) {
        return;
    }
    for (int i = 0; arr[i]; i++) {
        free(arr[i]);
    }
    free(arr);
}

static void apply_winsize(int fd, int cols, int rows) {
    if (fd < 0) {
        return;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ioctl(fd, TIOCSWINSZ, &ws);
}

#ifndef TIOCGPTN
#define TIOCGPTN _IOR('T', 0x30, unsigned int)
#endif

static int open_pty_master(char *slave_name, size_t slave_name_len) {
    int master = posix_openpt(O_RDWR | O_CLOEXEC | O_NOCTTY);
    if (master < 0) {
        master = open("/dev/ptmx", O_RDWR | O_CLOEXEC | O_NOCTTY);
    }
    if (master < 0) {
        return -1;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return -1;
    }
    int pty_num = 0;
    if (ioctl(master, TIOCGPTN, &pty_num) != 0) {
        close(master);
        return -1;
    }
    snprintf(slave_name, slave_name_len, "/dev/pts/%d", pty_num);
    return master;
}

static int login_on_slave(const char *slave_name, int cols, int rows) {
    if (setsid() < 0 && errno != EPERM) {
        return -1;
    }
    int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        return -1;
    }
#ifdef TIOCSCTTY
    ioctl(slave, TIOCSCTTY, 0);
#endif
    struct termios tio;
    if (tcgetattr(slave, &tio) == 0) {
        tio.c_iflag = ICRNL | IXON;
        tio.c_oflag = OPOST | ONLCR;
        tio.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
        tio.c_cflag = CS8 | CREAD | HUPCL;
        tio.c_cc[VMIN] = 1;
        tio.c_cc[VTIME] = 0;
        tcsetattr(slave, TCSANOW, &tio);
    }
    apply_winsize(slave, cols, rows);
    dup2(slave, STDIN_FILENO);
    dup2(slave, STDOUT_FILENO);
    dup2(slave, STDERR_FILENO);
    if (slave > STDERR_FILENO) {
        close(slave);
    }
    return 0;
}

static pid_t spawn_forkpty(int *master_fd, const char *cwd, char **argv, char **envp, int cols, int rows) {
    char slave_name[128];
    int master = open_pty_master(slave_name, sizeof(slave_name));
    if (master < 0) {
        return -1;
    }
    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        return -1;
    }
    if (pid == 0) {
        close(master);
        if (login_on_slave(slave_name, cols, rows) != 0) {
            _exit(126);
        }
        if (cwd && cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                chdir("/");
            }
        }
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGTSTP, SIG_DFL);
        signal(SIGTTIN, SIG_DFL);
        signal(SIGTTOU, SIG_DFL);
        if (envp) {
            execve(argv[0], argv, envp);
        } else {
            execv(argv[0], argv);
        }
        const char *msg = strerror(errno);
        write(STDERR_FILENO, "exec failed: ", 13);
        write(STDERR_FILENO, msg, strlen(msg));
        write(STDERR_FILENO, "\n", 1);
        _exit(127);
    }
    *master_fd = master;
    return pid;
}

static void *reader_loop(void *arg) {
    PtySession *session = (PtySession *) arg;
    JNIEnv *env = NULL;
    if ((*session->jvm)->AttachCurrentThread(session->jvm, &env, NULL) != 0) {
        LOGE("AttachCurrentThread failed");
        return NULL;
    }

    jclass cb_class = (*env)->GetObjectClass(env, session->callback);
    jmethodID on_data = (*env)->GetMethodID(env, cb_class, "onPtyOutput", "([B)V");
    jmethodID on_exit = (*env)->GetMethodID(env, cb_class, "onPtyExit", "(I)V");
    (*env)->DeleteLocalRef(env, cb_class);

    unsigned char buf[READ_BUF];
    while (session->running) {
        struct pollfd pfd;
        pfd.fd = session->master_fd;
        pfd.events = POLLIN | POLLHUP | POLLERR;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, 250);
        if (pr < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (pr == 0) {
            continue;
        }
        if (pfd.revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(session->master_fd, buf, sizeof(buf));
            if (n > 0 && on_data) {
                jbyteArray arr = (*env)->NewByteArray(env, (jsize) n);
                if (arr) {
                    (*env)->SetByteArrayRegion(env, arr, 0, (jsize) n, (const jbyte *) buf);
                    (*env)->CallVoidMethod(env, session->callback, on_data, arr);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionClear(env);
                    }
                    (*env)->DeleteLocalRef(env, arr);
                }
            } else if (n == 0) {
                break;
            } else if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                    continue;
                }
                break;
            }
        }
        if (pfd.revents & (POLLHUP | POLLERR)) {
            ssize_t extra;
            while ((extra = read(session->master_fd, buf, sizeof(buf))) > 0) {
                if (on_data) {
                    jbyteArray arr = (*env)->NewByteArray(env, (jsize) extra);
                    if (arr) {
                        (*env)->SetByteArrayRegion(env, arr, 0, (jsize) extra, (const jbyte *) buf);
                        (*env)->CallVoidMethod(env, session->callback, on_data, arr);
                        (*env)->DeleteLocalRef(env, arr);
                    }
                }
            }
            break;
        }
    }

    int status = 0;
    int exit_code = 0;
    if (session->child_pid > 0) {
        pid_t waited = waitpid(session->child_pid, &status, WNOHANG);
        if (waited == 0) {
            kill(session->child_pid, SIGHUP);
            usleep(80000);
            waited = waitpid(session->child_pid, &status, WNOHANG);
            if (waited == 0) {
                kill(session->child_pid, SIGKILL);
                waitpid(session->child_pid, &status, 0);
            }
        }
        if (WIFEXITED(status)) {
            exit_code = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exit_code = 128 + WTERMSIG(status);
        }
        if (on_exit) {
            (*env)->CallVoidMethod(env, session->callback, on_exit, exit_code);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
        }
    }

    session->running = 0;
    (*session->jvm)->DetachCurrentThread(session->jvm);
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_ai_opencode_cli_terminal_NativePty_nativeCreate(
        JNIEnv *env,
        jobject thiz,
        jstring cwd,
        jobjectArray argv,
        jobjectArray envp,
        jint cols,
        jint rows,
        jobject callback) {
    (void) thiz;
    if (argv == NULL || callback == NULL) {
        return 0;
    }

    int argc = 0;
    int envc = 0;
    char **cargv = copy_string_array(env, argv, &argc);
    char **cenvp = copy_string_array(env, envp, &envc);
    const char *ccwd = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    if (!cargv || argc < 1) {
        if (ccwd && cwd) {
            (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        }
        free_string_array(cargv);
        free_string_array(cenvp);
        return 0;
    }

    PtySession *session = (PtySession *) calloc(1, sizeof(PtySession));
    if (!session) {
        if (ccwd && cwd) {
            (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        }
        free_string_array(cargv);
        free_string_array(cenvp);
        return 0;
    }

    session->master_fd = -1;
    session->child_pid = -1;
    session->cols = cols > 0 ? cols : 80;
    session->rows = rows > 0 ? rows : 24;
    session->running = 1;
    (*env)->GetJavaVM(env, &session->jvm);
    session->callback = (*env)->NewGlobalRef(env, callback);

    int master = -1;
    pid_t pid = spawn_forkpty(&master, ccwd, cargv, cenvp, session->cols, session->rows);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        (*env)->DeleteGlobalRef(env, session->callback);
        free(session);
        if (ccwd && cwd) {
            (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        }
        free_string_array(cargv);
        free_string_array(cenvp);
        return 0;
    }

    session->master_fd = master;
    session->child_pid = pid;
    apply_winsize(master, session->cols, session->rows);
    set_nonblock(master);

    if (ccwd && cwd) {
        (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
    }
    free_string_array(cargv);
    free_string_array(cenvp);

    int err = pthread_create(&session->reader_thread, NULL, reader_loop, session);
    if (err != 0) {
        LOGE("pthread_create failed: %d", err);
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        close_fd(&session->master_fd);
        (*env)->DeleteGlobalRef(env, session->callback);
        free(session);
        return 0;
    }
    pthread_detach(session->reader_thread);

    LOGI("PTY started pid=%d master=%d", (int) pid, master);
    return (jlong) (intptr_t) session;
}

JNIEXPORT jint JNICALL
Java_ai_opencode_cli_terminal_NativePty_nativeWrite(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jbyteArray data) {
    (void) thiz;
    if (handle == 0 || data == NULL) {
        return -1;
    }
    PtySession *session = (PtySession *) (intptr_t) handle;
    if (session->master_fd < 0) {
        return -1;
    }
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) {
        return 0;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        return -1;
    }
    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(session->master_fd, bytes + written, (size_t) (len - written));
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(2000);
                continue;
            }
            (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
            return -1;
        }
        written += n;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT void JNICALL
Java_ai_opencode_cli_terminal_NativePty_nativeResize(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jint cols,
        jint rows) {
    (void) env;
    (void) thiz;
    if (handle == 0) {
        return;
    }
    PtySession *session = (PtySession *) (intptr_t) handle;
    session->cols = cols;
    session->rows = rows;
    apply_winsize(session->master_fd, cols, rows);
}

JNIEXPORT void JNICALL
Java_ai_opencode_cli_terminal_NativePty_nativeClose(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    (void) thiz;
    if (handle == 0) {
        return;
    }
    PtySession *session = (PtySession *) (intptr_t) handle;
    pthread_mutex_lock(&g_lock);
    session->running = 0;
    if (session->child_pid > 0) {
        kill(session->child_pid, SIGHUP);
        usleep(50000);
        kill(session->child_pid, SIGTERM);
    } else if (session->inprocess) {
        if (session->saved_stdin >= 0) {
            dup2(session->saved_stdin, STDIN_FILENO);
            close(session->saved_stdin);
            session->saved_stdin = -1;
        }
        if (session->saved_stdout >= 0) {
            dup2(session->saved_stdout, STDOUT_FILENO);
            close(session->saved_stdout);
            session->saved_stdout = -1;
        }
        if (session->saved_stderr >= 0) {
            dup2(session->saved_stderr, STDERR_FILENO);
            close(session->saved_stderr);
            session->saved_stderr = -1;
        }
    }
    close_fd(&session->master_fd);
    if (session->callback) {
        (*env)->DeleteGlobalRef(env, session->callback);
        session->callback = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jint JNICALL
Java_ai_opencode_cli_terminal_NativePty_nativeGetPid(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    (void) env;
    (void) thiz;
    if (handle == 0) {
        return -1;
    }
    PtySession *session = (PtySession *) (intptr_t) handle;
    return (jint) session->child_pid;
}

static void apply_env_array(char **envp, int envc) {
    for (int i = 0; i < envc; i++) {
        if (!envp[i]) {
            continue;
        }
        char *eq = strchr(envp[i], '=');
        if (!eq) {
            continue;
        }
        *eq = '\0';
        const char *key = envp[i];
        const char *value = eq + 1;
        if (key[0] != '\0') {
            setenv(key, value, 1);
        }
        *eq = '=';
    }
}

JNIEXPORT jlong JNICALL
Java_ai_opencode_cli_terminal_NativePty_nativeSetupTty(
        JNIEnv *env,
        jobject thiz,
        jstring cwd,
        jobjectArray envp,
        jint cols,
        jint rows,
        jobject callback) {
    (void) thiz;
    if (callback == NULL) {
        return 0;
    }

    int envc = 0;
    char **cenvp = copy_string_array(env, envp, &envc);
    const char *ccwd = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    char slave_name[128];
    int master = open_pty_master(slave_name, sizeof(slave_name));
    if (master < 0) {
        LOGE("open_pty_master failed: %s", strerror(errno));
        if (ccwd && cwd) {
            (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        }
        free_string_array(cenvp);
        return 0;
    }

    int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        LOGE("open slave failed: %s", strerror(errno));
        close(master);
        if (ccwd && cwd) {
            (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        }
        free_string_array(cenvp);
        return 0;
    }

    struct termios tio;
    if (tcgetattr(slave, &tio) == 0) {
        tio.c_iflag = ICRNL | IXON;
        tio.c_oflag = OPOST | ONLCR;
        tio.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
        tio.c_cflag = CS8 | CREAD | HUPCL;
        tio.c_cc[VMIN] = 1;
        tio.c_cc[VTIME] = 0;
        tcsetattr(slave, TCSANOW, &tio);
    }
    apply_winsize(slave, cols, rows);

    PtySession *session = (PtySession *) calloc(1, sizeof(PtySession));
    if (!session) {
        close(slave);
        close(master);
        if (ccwd && cwd) {
            (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        }
        free_string_array(cenvp);
        return 0;
    }

    session->master_fd = master;
    session->child_pid = -1;
    session->inprocess = 1;
    session->saved_stdin = -1;
    session->saved_stdout = -1;
    session->saved_stderr = -1;
    session->cols = cols > 0 ? cols : 80;
    session->rows = rows > 0 ? rows : 24;
    session->running = 1;
    (*env)->GetJavaVM(env, &session->jvm);
    session->callback = (*env)->NewGlobalRef(env, callback);

    if (cenvp) {
        apply_env_array(cenvp, envc);
    }

    if (ccwd && ccwd[0] != '\0') {
        if (chdir(ccwd) != 0) {
            chdir("/");
        }
    }

    fflush(stdout);
    fflush(stderr);
    session->saved_stdin = dup(STDIN_FILENO);
    session->saved_stdout = dup(STDOUT_FILENO);
    session->saved_stderr = dup(STDERR_FILENO);
    dup2(slave, STDIN_FILENO);
    dup2(slave, STDOUT_FILENO);
    dup2(slave, STDERR_FILENO);
    if (slave > STDERR_FILENO) {
        close(slave);
    }

    set_nonblock(master);

    if (ccwd && cwd) {
        (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
    }
    free_string_array(cenvp);

    int err = pthread_create(&session->reader_thread, NULL, reader_loop, session);
    if (err != 0) {
        LOGE("pthread_create failed: %d", err);
        close_fd(&session->master_fd);
        (*env)->DeleteGlobalRef(env, session->callback);
        free(session);
        return 0;
    }
    pthread_detach(session->reader_thread);

    LOGI("in-process TTY ready master=%d", master);
    return (jlong) (intptr_t) session;
}

JNIEXPORT jint JNICALL
Java_ai_opencode_cli_terminal_NativePty_nativeStartNode(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jstring lib_path,
        jobjectArray argv) {
    (void) thiz;
    if (handle == 0) {
        return -1;
    }
    PtySession *session = (PtySession *) (intptr_t) handle;

    int argc = 0;
    char **cargv = copy_string_array(env, argv, &argc);
    if (!cargv || argc < 1) {
        free_string_array(cargv);
        return -1;
    }

    const char *lib = lib_path ? (*env)->GetStringUTFChars(env, lib_path, NULL) : NULL;
    void *module = NULL;
    if (lib && lib[0] != '\0') {
        module = dlopen(lib, RTLD_NOW | RTLD_GLOBAL);
    }
    if (module == NULL) {
        module = dlopen("libnode.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (module == NULL) {
        const char *err = dlerror();
        LOGE("dlopen libnode failed: %s", err ? err : "unknown");
        if (lib && lib_path) {
            (*env)->ReleaseStringUTFChars(env, lib_path, lib);
        }
        free_string_array(cargv);
        return -1;
    }

    typedef int (*node_start_fn)(int, char **);
    node_start_fn start = (node_start_fn) dlsym(module, "_ZN4node5StartEiPPc");
    if (start == NULL) {
        start = (node_start_fn) dlsym(module, "node_start");
    }
    if (start == NULL) {
        LOGE("dlsym node::Start failed: %s", dlerror());
        if (lib && lib_path) {
            (*env)->ReleaseStringUTFChars(env, lib_path, lib);
        }
        free_string_array(cargv);
        return -1;
    }

    if (lib && lib_path) {
        (*env)->ReleaseStringUTFChars(env, lib_path, lib);
    }

    LOGI("calling node::Start argc=%d argv0=%s", argc, cargv[0]);
    int code = start(argc, cargv);
    LOGI("node::Start returned %d", code);
    free_string_array(cargv);

    if (session->callback) {
        jclass cb_class = (*env)->GetObjectClass(env, session->callback);
        jmethodID on_exit = (*env)->GetMethodID(env, cb_class, "onPtyExit", "(I)V");
        if (on_exit) {
            (*env)->CallVoidMethod(env, session->callback, on_exit, code);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
        }
        (*env)->DeleteLocalRef(env, cb_class);
    }

    session->running = 0;
    return code;
}
