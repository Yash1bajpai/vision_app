#!/usr/bin/env python3
"""Fault-inject actual nativeLoad body with host API stubs, not a model run.

Requires a C++17 compiler. Does not prove Android ABI or native model behavior.
Checks only ownership transfer and cleanup for success/null/exception branches.
"""
from pathlib import Path
import os
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
source = (ROOT / "app/src/main/cpp/vision_llama_jni.cpp").read_text()
start = source.index("JNIEXPORT jlong JNICALL")
end = source.index("\nJNIEXPORT void JNICALL", start)
with tempfile.TemporaryDirectory(prefix="vision-load-ownership-") as directory:
    directory = Path(directory)
    unit = directory / "test.cpp"
    unit.write_text('#include <cassert>\n#include <chrono>\n#include <cstdio>\n#include <memory>\n#include <stdexcept>\n#define JNIEXPORT\n#define JNICALL\n#define LOGE(...) ((void)0)\n#define LOGI(...) ((void)0)\nusing jlong=long long; using jint=int; using jclass=void*; using jstring=const char*;\nstruct JNIEnv {};\nstatic int mode, models, contexts;\nstruct llama_model{}; struct llama_context{};\nstruct llama_model_params{}; struct llama_context_params{unsigned n_ctx; int n_threads,n_threads_batch;};\nllama_model_params llama_model_default_params(){return {};}\nllama_context_params llama_context_default_params(){return {};}\nllama_model* llama_model_load_from_file(const char*,llama_model_params){if(mode==1)return nullptr; ++models;return new llama_model;}\nllama_context* llama_init_from_model(llama_model*,llama_context_params){if(mode==2)throw std::runtime_error("context fault");if(mode==3)return nullptr;++contexts;return new llama_context;}\nvoid llama_model_free(llama_model* p){if(p){assert(contexts==0);--models;delete p;}}\nvoid llama_free(llama_context* p){if(p){assert(models==1);--contexts;delete p;}}\nvoid ensure_backend_init(){}\nstruct JniStringChars {const char* p; JniStringChars(JNIEnv*,jstring x):p(x){} const char* c_str()const{return p;}};\nstruct VisionLlamaHandle {llama_model* model=nullptr;llama_context* ctx=nullptr;VisionLlamaHandle(){if(mode==4)throw std::bad_alloc();}};\n' + source[start:end]
                    + 'int main(){\n JNIEnv env;\n if (Java_com_vision_app_LlamaBridge_nativeLoad(&env,nullptr,nullptr,1024,4)!=0) return 2;\n for(mode=0;mode<=4;++mode){\n  models=contexts=0;\n  auto h=Java_com_vision_app_LlamaBridge_nativeLoad(&env,nullptr,"fixture",1024,4);\n  if(mode==0){assert(h);auto p=reinterpret_cast<VisionLlamaHandle*>(h);llama_free(p->ctx);llama_model_free(p->model);delete p;}\n  else assert(h==0);\n  if(models||contexts){printf("FAIL fault=%d leaked models=%d contexts=%d\\n",mode,models,contexts);return 1;}\n }\n puts("PASS normal load plus model-null/context-throw/context-null/handle-construction-throw: no leaked ownership");\n}\n')
    executable = directory / "test"
    subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra",
                    "-Wno-unused-variable", "-fsanitize=address,undefined", "-g",
                    str(unit), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
