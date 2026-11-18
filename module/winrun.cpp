#include <sys/types.h>
#include <unistd.h>
// Zygisk module: inject into com.xiaomi.winplay and call WindowChannel.runNewApp
#include <jni.h>
#include <stdint.h>
#include "zygisk.hpp"

extern "C" {
int open(const char *path, int flags, ...);
long read(int fd, void *buf, unsigned long n);
long write(int fd, const void *buf, unsigned long n);
int close(int fd);
int usleep(unsigned int us);
int pthread_create(unsigned long *th, const void *attr, void *(*fn)(void *), void *arg);
int pthread_detach(unsigned long th);
}

#define CMD_FILE "/storage/emulated/0/wpz/cmd2.txt"
#define RES_FILE "/storage/emulated/0/wpz/res2.log"

static JavaVM *g_vm = nullptr;
static int g_target = 0;
static int g_last_seq = -1;

static int slen(const char *s) { int n = 0; while (s[n]) n++; return n; }
static void wstr(int fd, const char *s) { write(fd, s, (unsigned long) slen(s)); }
static void wln(int fd, const char *s) { wstr(fd, s); write(fd, "\n", 1); }
static void wnum(int fd, long v) {
    char b[24]; int i = 23; b[i] = 0;
    if (v == 0) b[--i] = '0';
    int neg = v < 0; if (neg) v = -v;
    while (v > 0 && i > 0) { b[--i] = (char) ('0' + (v % 10)); v /= 10; }
    if (neg && i > 0) b[--i] = '-';
    wln(fd, b + i);
}
static void logmsg(const char *s) {
    int fd = open(RES_FILE, 0x441, 0644);
    if (fd >= 0) { wln(fd, s); close(fd); }
}
static void logkv(const char *k, long v) {
    int fd = open(RES_FILE, 0x441, 0644);
    if (fd >= 0) { wstr(fd, k); wnum(fd, v); close(fd); }
}


static jobjectArray mk_arr(JNIEnv *env, const char *csv, const char *path, int unixpath);
static void do_run_argv(JNIEnv *env, jobject chan, jclass wc, const char *path, int unixpath,
                        const char *args, const char *envs);
static void logmsg(const char *s);
static void logkv(const char *k, long v);

static jobject load_class(JNIEnv *env, jobject cl, const char *name) {
    jclass clc = env->FindClass("java/lang/ClassLoader");
    if (!clc) return nullptr;
    jmethodID m = env->GetMethodID(clc, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!m) return nullptr;
    jstring s = env->NewStringUTF(name);
    jobject c = env->CallObjectMethod(cl, m, s);
    env->DeleteLocalRef(s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    return c;
}

static void do_run(JNIEnv *env, jobject cl, jclass wc, const char *path, int unixpath, const char *args, const char *envs) {
    jclass mwm = (jclass) load_class(env, cl, "com.xiaomi.winplay.multiwindow.module.MultiWindowManger");
    if (!mwm) { logmsg("ERR load MultiWindowManger"); return; }
    jmethodID gwc = env->GetStaticMethodID(mwm, "getWindowChannel",
        "()Lcom/xiaomi/winplay/multiwindow/module/WindowChannel;");
    if (!gwc) { env->ExceptionClear(); logmsg("ERR no getWindowChannel"); return; }
    jobject chan = env->CallStaticObjectMethod(mwm, gwc);
    if (env->ExceptionCheck()) { env->ExceptionClear(); logmsg("ERR getWindowChannel threw"); return; }
    if (!chan) { logmsg("ERR channel null"); return; }
    logmsg("channel ok");

    jclass hmc = env->FindClass("java/util/HashMap");
    jmethodID ini = env->GetMethodID(hmc, "<init>", "()V");
    jobject hm = env->NewObject(hmc, ini);
    jmethodID put = env->GetMethodID(hmc, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jstring k = env->NewStringUTF("MI_HACK_RESOLUTION_SCALE");
    jstring v = env->NewStringUTF("100");
    env->CallObjectMethod(hm, put, k, v);
    env->DeleteLocalRef(k); env->DeleteLocalRef(v);

    if (args && *args) { do_run_argv(env, chan, wc, path, unixpath, args, envs); return; }

    jmethodID rna = env->GetMethodID(wc, "runNewApp", "(Ljava/lang/String;Ljava/util/HashMap;Z)V");
    if (!rna) { env->ExceptionClear(); logmsg("ERR no runNewApp"); return; }
    jstring p = env->NewStringUTF(path);
    logmsg("calling runNewApp");
    logkv("  unixpath=", unixpath);
    env->CallVoidMethod(chan, rna, p, hm, (jboolean) (unixpath ? JNI_TRUE : JNI_FALSE));
    if (env->ExceptionCheck()) { env->ExceptionClear(); logmsg("ERR runNewApp threw"); }
    else logmsg("runNewApp returned OK");
    env->DeleteLocalRef(p);
}


static jobjectArray mk_arr(JNIEnv *env, const char *csv, const char *path, int unixpath) {
    // count entries
    int cnt = 0; const char *q = csv;
    if (q) { cnt = 1; for (const char *t = q; *t; t++) if (*t == ';') cnt++; }
    int extra = (path ? 1 : 0) + ((path && unixpath) ? 1 : 0);
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(cnt + extra, sc, nullptr);
    int i = 0;
    if (path) {
        if (unixpath) env->SetObjectArrayElement(arr, i++, env->NewStringUTF("/unix"));
        env->SetObjectArrayElement(arr, i++, env->NewStringUTF(path));
    }
    if (csv) {
        const char *st = csv;
        char tmp[1024];
        while (*st) {
            const char *e = st; while (*e && *e != ';') e++;
            int n = (int)(e - st); if (n > 1000) n = 1000;
            for (int k = 0; k < n; k++) tmp[k] = st[k];
            tmp[n] = 0;
            env->SetObjectArrayElement(arr, i++, env->NewStringUTF(tmp));
            if (!*e) break;
            st = e + 1;
        }
    }
    return arr;
}

static void do_run_argv(JNIEnv *env, jobject chan, jclass wc, const char *path, int unixpath,
                        const char *args, const char *envs) {
    jmethodID wrun = env->GetMethodID(wc, "wine_run_app",
        "([Ljava/lang/String;[Ljava/lang/String;)V");
    if (!wrun) { env->ExceptionClear(); logmsg("ERR no wine_run_app"); return; }
    jobjectArray a1 = mk_arr(env, args, path, unixpath);
    jobjectArray a2 = mk_arr(env, envs, nullptr, 0);
    logmsg("calling wine_run_app");
    env->CallVoidMethod(chan, wrun, a1, a2);
    if (env->ExceptionCheck()) { env->ExceptionClear(); logmsg("ERR wine_run_app threw"); }
    else logmsg("wine_run_app returned OK");
}

static int parse_int(const char *s) {
    int v = 0, neg = 0;
    if (*s == '-') { neg = 1; s++; }
    while (*s >= '0' && *s <= '9') { v = v * 10 + (*s - '0'); s++; }
    return neg ? -v : v;
}

static void *worker(void *) {
    JNIEnv *env = nullptr;
    if (g_vm->AttachCurrentThread((void **) &env, nullptr) != JNI_OK) return nullptr;
    logmsg("worker start");

    jclass at = env->FindClass("android/app/ActivityThread");
    if (!at) { logmsg("ERR no ActivityThread"); return nullptr; }
    jmethodID mcur = env->GetStaticMethodID(at, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jmethodID mapp = env->GetMethodID(at, "getApplication", "()Landroid/app/Application;");

    jobject cl = nullptr;
    for (int i = 0; i < 1200 && !cl; i++) {
        jobject cur = env->CallStaticObjectMethod(at, mcur);
        if (cur) {
            jobject app = env->CallObjectMethod(cur, mapp);
            if (app) {
                jclass appc = env->GetObjectClass(app);
                jmethodID mcl = env->GetMethodID(appc, "getClassLoader", "()Ljava/lang/ClassLoader;");
                cl = env->CallObjectMethod(app, mcl);
                if (cl) logmsg("got classloader");
                env->DeleteLocalRef(appc);
            }
            env->DeleteLocalRef(cur);
        }
        env->ExceptionClear();
        if (!cl) usleep(500000);
    }
    if (!cl) { logmsg("ERR no classloader"); return nullptr; }

    jobject wc = load_class(env, cl, "com.xiaomi.winplay.multiwindow.module.WindowChannel");
    if (!wc) { logmsg("ERR load WindowChannel"); return nullptr; }
    logmsg("WindowChannel loaded");

    for (;;) {
        int fd = open(CMD_FILE, 0 /*O_RDONLY*/);
        if (fd >= 0) {
            char buf[8192];
            long n = read(fd, buf, sizeof(buf) - 1);
            close(fd);
            if (n > 0) {
                buf[n] = 0;
                char *p = buf;
                while (*p) {
                    char *nl = p;
                    while (*nl && *nl != '\n') nl++;
                    char save = *nl;
                    *nl = 0;
                    if (*p) {
                        char *p1 = p;
                        while (*p1 && *p1 != '|') p1++;
                        if (*p1 == '|') {
                            *p1 = 0;
                            char *p2 = p1 + 1;
                            while (*p2 && *p2 != '|') p2++;
                            int unixpath = 1, seq = -1;
                            char *args = 0, *envs = 0;
                            if (*p2 == '|') {
                                *p2 = 0; unixpath = parse_int(p1 + 1);
                                char *p3 = p2 + 1;
                                while (*p3 && *p3 != '|') p3++;
                                if (*p3 == '|') { *p3 = 0; seq = parse_int(p2 + 1);
                                    char *p4 = p3 + 1;
                                    while (*p4 && *p4 != '|') p4++;
                                    if (*p4 == '|') { *p4 = 0; args = p3 + 1; envs = p4 + 1; }
                                    else { args = p3 + 1; }
                                } else seq = parse_int(p2 + 1);
                            }
                            else unixpath = parse_int(p1 + 1);
                            if (!(seq >= 0 && seq <= g_last_seq)) {
                                if (seq >= 0) g_last_seq = seq;
                                logkv("cmd seq=", seq);
                                do_run(env, cl, (jclass) wc, p, unixpath, args, envs);
                            }
                        }
                    }
                    if (!save) break;
                    p = nl + 1;
                }
            }
        }
        usleep(500000);
    }
    return nullptr;
}

class Mod : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override { this->api = api; this->env = env; }
    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (!args || !args->nice_name) return;
        const char *nice = env->GetStringUTFChars(args->nice_name, nullptr);
        if (nice) {
            if (strcmp_simple(nice, "com.xiaomi.winplay")) g_target = 1;
            env->ReleaseStringUTFChars(args->nice_name, nice);
        }
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!g_target) return;
        env->GetJavaVM(&g_vm);
        unsigned long t = 0;
        pthread_create(&t, nullptr, worker, nullptr);
        pthread_detach(t);
    }
private:
    static int strcmp_simple(const char *a, const char *b) {
        while (*a && *a == *b) { a++; b++; }
        return *a == *b;
    }
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(Mod)
