#!/usr/bin/env python3
"""Host regression for mandatory-grammar setup; no weights or generation run.

Extracts the actual JNI entry up to its generation sampler setup. Runtime APIs
are stubs, so this proves missing grammar is rejected before unconstrained setup,
not that a real GBNF parser or model behaves correctly. Requires C++17 compiler.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / "app/src/main/cpp/vision_llama_jni.cpp").read_text()
start = source.index("JNIEXPORT jbyteArray JNICALL\nJava_com_vision_app_LlamaBridge_nativeGenerate")
end = source.index("    llama_sampler_chain_add(smpl, llama_sampler_init_dist", start)
PREFIX = r'''#include <string>
#include <vector>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <cassert>
#define JNIEXPORT
#define JNICALL
#define LOGE(...) ((void)0)
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
using jlong=long long; using jint=int; using jclass=void*; using jstring=const char*; using jbyteArray=void*;
struct JNIEnv{}; struct llama_context{};struct llama_model{};struct llama_vocab{};
using llama_token=int; struct llama_sampler {bool constrained=false;};
static int grammarCalls=0;
struct VisionLlamaHandle {llama_model* model;llama_context* ctx;};
struct JniStringChars {const char* p;JniStringChars(JNIEnv*,jstring s):p(s){} const char* c_str(){return p;}};
struct GenerateAbortState {std::chrono::steady_clock::time_point deadline;};
struct AbortGuard {explicit AbortGuard(llama_context*){}};
bool generate_abort_callback(void*){return false;}
void llama_set_abort_callback(llama_context*,bool(*)(void*),void*){}
const llama_vocab* llama_model_get_vocab(llama_model*){static llama_vocab v;return &v;}
int llama_tokenize(const llama_vocab*,const char*,int,llama_token* out,int,bool,bool){if(!out)return -1;*out=1;return 1;}
int llama_sampler_chain_default_params(){return 0;}
llama_sampler* llama_sampler_chain_init(int){return new llama_sampler;}
llama_sampler* llama_sampler_init_grammar(const llama_vocab*,const char* g,const char*){++grammarCalls;return !strcmp(g,"valid")?new llama_sampler{true}:nullptr;}
void llama_sampler_free(llama_sampler* s){delete s;}
void llama_sampler_chain_add(llama_sampler* s,llama_sampler* g){s->constrained=g->constrained;delete g;}
'''
SUFFIX = r'''    bool constrained=smpl->constrained;
    llama_sampler_free(smpl);
    return constrained?reinterpret_cast<void*>(1):reinterpret_cast<void*>(2);
}
int main(){JNIEnv env;llama_model m;llama_context c;VisionLlamaHandle h{&m,&c};int failures=0;
 for(const char* grammar:{static_cast<const char*>(nullptr),"","invalid","valid"}){
  grammarCalls=0;auto out=Java_com_vision_app_LlamaBridge_nativeGenerate(&env,nullptr,reinterpret_cast<jlong>(&h),"safe fixture",grammar,16,8000);
  bool pass=grammar && !strcmp(grammar,"valid")?out==reinterpret_cast<void*>(1):out==nullptr;
  printf("grammar %s: %s (parse calls=%d)\n",grammar?grammar:"NULL",pass?"PASS":"FAIL",grammarCalls);failures+=!pass;
 }
 return failures?1:0;
}
'''
with tempfile.TemporaryDirectory(prefix="vision-grammar-guard-") as directory:
    directory = Path(directory)
    unit = directory / "test.cpp"
    unit.write_text(PREFIX + source[start:end] + SUFFIX)
    executable = directory / "test"
    subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra",
                    "-Wno-unused-variable", "-Wno-unused-parameter",
                    "-fsanitize=address,undefined", str(unit), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
